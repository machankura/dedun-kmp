package com.machankura.dedun.compose.ui.view.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.machankura.dedun.compose.wallet.wavelength.IdentityWavelength
import com.machankura.dedun.compose.wallet.wavelength.WalletExpiry
import com.machankura.dedun.compose.wallet.wavelength.WalletRecovery
import com.machankura.dedun.compose.wallet.wavelength.WalletState
import com.machankura.dedun.compose.wallet.wavelength.WalletUnavailability
import engineering.lightning.wavelength.ListRequest
import engineering.lightning.wavelength.Network
import engineering.lightning.wavelength.WavelengthException
import engineering.lightning.wavelength.engine.RuntimePhase
import engineering.lightning.wavelength.engine.WalletEngine
import engineering.lightning.wavelength.engine.WalletSnapshot
import engineering.lightning.wavelength.model.Balance
import engineering.lightning.wavelength.model.Entry
import engineering.lightning.wavelength.model.EntryStatus
import engineering.lightning.wavelength.model.ListView
import engineering.lightning.wavelength.model.ServerInfo
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** What the profile's wallet row and the wallet screen draw -- `docs/wavelength-wallet.md`, Phase 5. */
sealed interface WavelengthWalletUIState {
    data object Loading : WavelengthWalletUIState

    data class Unavailable(val reason: WalletUnavailability) : WavelengthWalletUIState

    data object Opening : WavelengthWalletUIState

    data object Restoring : WavelengthWalletUIState

    data object Failed : WavelengthWalletUIState

    /** The directory holds another identity's wallet. Nothing here can fix it, and a retry won't. */
    data object WrongIdentity : WavelengthWalletUIState

    data class Loaded(
        val network: Network,
        val balance: Balance,
        /** Newest first, with what is still pending on top. */
        val entries: List<Entry>,
        /** Catching up with the chain: the balance may still move. */
        val syncing: Boolean,
        val recovery: WalletRecovery?,
        val server: ServerInfo?,
        /** Proofs of payment kept across restarts, by payment hash: the daemon reports each once. */
        val preimages: Map<String, String> = emptyMap(),
        val expiry: WalletExpiry = WalletExpiry.Nothing,
    ) : WavelengthWalletUIState {
        fun preimageOf(entry: Entry): String? {
            val progress = entry.progress ?: return null
            return progress.preimage.ifEmpty { null } ?: preimages[progress.paymentHash]
        }

        val pendingSat: Long get() = balance.pendingInSat + balance.pendingOutSat

        /** Held on the swap server's ledger rather than in a coin of the wallet's. Never part of the balance. */
        val creditSat: Long get() = balance.creditAvailableSat + balance.creditReservedSat
    }
}

/**
 * The active profile's wallet, read for a screen. The engine keeps its snapshot current from the
 * daemon's activity stream, so there is nothing here to poll. A new engine arrives through
 * [walletState] every time the daemon reopens.
 *
 * Takes the holder's state and its two actions rather than the holder, so a test can drive it
 * with a `MutableStateFlow` and an engine over the fake client.
 */
class WavelengthWalletViewModel(
    private val walletState: StateFlow<WalletState>,
    private val onConsent: () -> Unit,
    private val onRetry: () -> Unit,
) : ViewModel() {
    var uiState: WavelengthWalletUIState by mutableStateOf(WavelengthWalletUIState.Loading)
        private set

    private var engine: WalletEngine? = null
    private var readAt: Balance? = null
    private var expiry: WalletExpiry = WalletExpiry.Nothing

    init {
        viewModelScope.launch {
            walletState.collectLatest { state ->
                engine = (state as? WalletState.Open)?.engine
                when (state) {
                    WalletState.None -> uiState = WavelengthWalletUIState.Loading
                    is WalletState.Unavailable -> uiState = WavelengthWalletUIState.Unavailable(state.reason)
                    WalletState.Opening -> uiState = WavelengthWalletUIState.Opening
                    WalletState.Recovering -> uiState = WavelengthWalletUIState.Restoring
                    is WalletState.Failed -> uiState = WavelengthWalletUIState.Failed
                    WalletState.WrongIdentity -> uiState = WavelengthWalletUIState.WrongIdentity
                    is WalletState.Open -> state.engine.snapshot.collect { snapshot ->
                        val balance = snapshot.balance
                        if (balance != readAt) {
                            readAt = balance
                            expiry = readExpiry(state, snapshot)
                        }
                        uiState = snapshot.toUIState(state, expiry)
                    }
                }
            }
        }
    }

    /** The coins change only with the balance, so that is when they are read again. */
    private suspend fun readExpiry(open: WalletState.Open, snapshot: WalletSnapshot): WalletExpiry {
        val info = snapshot.info ?: return expiry
        return try {
            val coins = open.engine.list(ListRequest(ListView.VTXOS)).vtxos?.vtxos.orEmpty()
            WalletExpiry.of(coins, info.blockHeight, info.serverInfo?.freeRefreshWindowBlocks ?: 0)
        } catch (e: WavelengthException) {
            expiry
        }
    }

    /** The first-open sheet's *Turn on*. */
    fun turnOn() = onConsent()

    fun retry() = onRetry()

    /** Reads everything again, as a screen is shown. */
    fun refresh() {
        val engine = engine ?: return
        viewModelScope.launch {
            try {
                engine.refresh()
            } catch (e: WavelengthException) {
                // The failure is on the snapshot, which is what the screen draws.
            }
        }
    }

    companion object {
        fun factory(identityWavelength: IdentityWavelength): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                WavelengthWalletViewModel(
                    walletState = identityWavelength.state,
                    onConsent = identityWavelength::consent,
                    onRetry = identityWavelength::retry,
                )
            }
        }
    }
}

private fun WalletSnapshot.toUIState(open: WalletState.Open, expiry: WalletExpiry): WavelengthWalletUIState {
    val balance = balance
    return when {
        phase == RuntimePhase.ERROR -> WavelengthWalletUIState.Failed
        balance == null -> WavelengthWalletUIState.Opening
        else -> WavelengthWalletUIState.Loaded(
            network = open.network,
            balance = balance,
            entries = activity.sortedWith(
                compareBy<Entry> { it.status != EntryStatus.PENDING }.thenByDescending { it.createdAt },
            ),
            syncing = phase != RuntimePhase.READY,
            recovery = open.recovery,
            server = info?.serverInfo,
            preimages = open.preimages.value,
            expiry = expiry,
        )
    }
}
