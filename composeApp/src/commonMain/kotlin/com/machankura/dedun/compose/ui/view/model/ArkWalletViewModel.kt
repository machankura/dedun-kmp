package com.machankura.dedun.compose.ui.view.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.machankura.dedun.compose.wallet.ark.ArkBalance
import com.machankura.dedun.compose.wallet.ark.ArkError
import com.machankura.dedun.compose.wallet.ark.ArkEvent
import com.machankura.dedun.compose.wallet.ark.ArkExpiry
import com.machankura.dedun.compose.wallet.ark.ArkMovement
import com.machankura.dedun.compose.wallet.ark.ArkNetwork
import com.machankura.dedun.compose.wallet.ark.ArkRecovery
import com.machankura.dedun.compose.wallet.ark.ArkUnavailability
import com.machankura.dedun.compose.wallet.ark.ArkWallet
import com.machankura.dedun.compose.wallet.ark.ArkWalletState
import com.machankura.dedun.compose.wallet.ark.IdentityArk
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the profile's wallet row and the wallet screen draw -- `docs/bark-wallet.md`, Phase 4. */
sealed interface ArkWalletUIState {
    data object Loading : ArkWalletUIState

    data class Unavailable(val reason: ArkUnavailability) : ArkWalletUIState

    data object Opening : ArkWalletUIState

    data object Restoring : ArkWalletUIState

    /** The wallet did not open, or opened and could not be read. */
    data class Failed(val error: ArkError, val opened: Boolean) : ArkWalletUIState

    data class Loaded(
        val network: ArkNetwork,
        val balance: ArkBalance,
        /** Newest first. */
        val movements: List<ArkMovement>,
        val expiry: ArkExpiry,
        val recovery: ArkRecovery,
    ) : ArkWalletUIState {
        /** The scan on the open that created the wallet missed something. */
        val recoveryIncomplete: Boolean
            get() = recovery is ArkRecovery.Failed || (recovery is ArkRecovery.Completed && !recovery.complete)
    }
}

/**
 * The active profile's Ark wallet, read for a screen: the balance, the history and the
 * earliest deadline, kept current by the wallet's own events rather than by polling.
 *
 * Takes the holder's state and its two actions rather than the holder, so a test can
 * drive it with a `MutableStateFlow` and a fake wallet.
 */
class ArkWalletViewModel(
    private val walletState: StateFlow<ArkWalletState>,
    private val onConsent: () -> Unit,
    private val onRetry: () -> Unit,
) : ViewModel() {
    var uiState: ArkWalletUIState by mutableStateOf(ArkWalletUIState.Loading)
        private set

    private val reading = Mutex()
    private var open: ArkWallet? = null

    init {
        viewModelScope.launch {
            walletState.collectLatest { state ->
                open = (state as? ArkWalletState.Open)?.wallet
                when (state) {
                    ArkWalletState.None -> uiState = ArkWalletUIState.Loading
                    is ArkWalletState.Unavailable -> uiState = ArkWalletUIState.Unavailable(state.reason)
                    ArkWalletState.Opening -> uiState = ArkWalletUIState.Opening
                    ArkWalletState.Recovering -> uiState = ArkWalletUIState.Restoring
                    is ArkWalletState.Failed -> uiState = ArkWalletUIState.Failed(state.error, opened = false)
                    is ArkWalletState.Open -> coroutineScope {
                        read(state.wallet, state.recovery)
                        launch { state.wallet.events.collect { event -> apply(state.wallet, state.recovery, event) } }
                    }
                }
            }
        }
    }

    /** The first-open sheet's *Turn on*. */
    fun turnOn() = onConsent()

    /** A retry from the error state: reopen if the open failed, read again if the read did. */
    fun retry() {
        val state = uiState
        val wallet = open
        if (state is ArkWalletUIState.Failed && state.opened && wallet != null) {
            viewModelScope.launch { read(wallet, (walletState.value as? ArkWalletState.Open)?.recovery ?: ArkRecovery.NotRun) }
        } else {
            onRetry()
        }
    }

    /** Moves the onchain balance onto Ark. The board then shows in the history as it progresses. */
    fun board(onStarted: () -> Unit, onFailure: () -> Unit) {
        val wallet = open ?: return
        viewModelScope.launch {
            try {
                wallet.boardAll()
                onStarted()
                refresh()
            } catch (e: ArkError) {
                onFailure()
            }
        }
    }

    /** Reads everything again, as a screen is shown. */
    fun refresh() {
        val wallet = open ?: return
        val recovery = (walletState.value as? ArkWalletState.Open)?.recovery ?: ArkRecovery.NotRun
        viewModelScope.launch { read(wallet, recovery) }
    }

    private suspend fun read(wallet: ArkWallet, recovery: ArkRecovery) = reading.withLock {
        uiState = try {
            val tip = wallet.tipHeight()
            ArkWalletUIState.Loaded(
                network = wallet.network,
                balance = wallet.balance(),
                movements = wallet.history(),
                expiry = ArkExpiry.of(wallet.firstExpiryHeight(), tip, ArkExpiry.thresholdFor(wallet.network)),
                recovery = recovery,
            )
        } catch (e: ArkError) {
            ArkWalletUIState.Failed(e, opened = true)
        }
    }

    /** A movement replaces the row with its id, or goes on top; the balance is read again. */
    private suspend fun apply(wallet: ArkWallet, recovery: ArkRecovery, event: ArkEvent) {
        val loaded = uiState as? ArkWalletUIState.Loaded
        if (event is ArkEvent.Lagging || loaded == null) {
            read(wallet, recovery)
            return
        }
        val movement = (event as ArkEvent.MovementChanged).movement
        val movements = if (loaded.movements.any { it.id == movement.id }) {
            loaded.movements.map { if (it.id == movement.id) movement else it }
        } else {
            listOf(movement) + loaded.movements
        }
        val balance = try { wallet.balance() } catch (e: ArkError) { loaded.balance }
        uiState = loaded.copy(movements = movements, balance = balance)
    }

    companion object {
        fun factory(identityArk: IdentityArk): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ArkWalletViewModel(
                    walletState = identityArk.state,
                    onConsent = identityArk::consent,
                    onRetry = identityArk::retry,
                )
            }
        }
    }
}
