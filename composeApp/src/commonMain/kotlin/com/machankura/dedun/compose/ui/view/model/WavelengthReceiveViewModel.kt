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
import com.machankura.dedun.compose.wallet.wavelength.WalletState
import com.machankura.dedun.compose.wallet.wavelength.WavelengthWalletRollout
import engineering.lightning.wavelength.Network
import engineering.lightning.wavelength.ReceiveRequest
import engineering.lightning.wavelength.WavelengthException
import engineering.lightning.wavelength.engine.WalletEngine
import engineering.lightning.wavelength.model.EntryKind
import engineering.lightning.wavelength.model.EntryStatus
import fr.acinq.bitcoin.Satoshi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Why a payment request could not be made. */
enum class WavelengthReceiveProblem {
    /** Lightning needs an amount: the SDK has no amountless invoice. */
    NeedsAmount,

    /** It would take the wallet past the cap this app sets on mainnet (D5). */
    AboveCap,

    /** More than the operator takes in one coin. */
    AboveCoinLimit,

    /** More than the operator lets one wallet hold. */
    AboveBalanceLimit,

    /**
     * The daemon timed out, and an invoice may exist anyway. The activity is read again before
     * anything else is offered, so a second invoice is never made over the first.
     */
    Uncertain,

    CouldNotMake,
}

/** What the receive screen draws -- `docs/wavelength-wallet.md`, Phase 6. */
data class WavelengthReceiveUIState(
    val hasWallet: Boolean = false,
    val network: Network = WavelengthWalletRollout.NETWORK,
    val amount: String = "",
    val working: Boolean = false,
    val problem: WavelengthReceiveProblem? = null,
    val invoice: String? = null,
    /** The operator's floor made this invoice's payment credit, and not a coin. */
    val invoiceIsCredit: Boolean = false,
    val address: String? = null,
)

/**
 * Being paid. Lightning and onchain are two requests here because the daemon has no one URI that
 * carries both, and the onchain one needs nothing from the person, so it is only made when asked.
 */
class WavelengthReceiveViewModel(private val walletState: StateFlow<WalletState>) : ViewModel() {
    var uiState by mutableStateOf(WavelengthReceiveUIState(hasWallet = walletState.value is WalletState.Open))
        private set

    private val open: WalletState.Open? get() = walletState.value as? WalletState.Open

    fun onAmountChange(text: String) {
        val digits = text.filter(Char::isDigit)
        uiState = uiState.copy(amount = digits, invoice = null, invoiceIsCredit = false, problem = null)
    }

    /** Asks for an invoice for the amount typed. */
    fun makeInvoice() {
        val open = open ?: return
        val amount = uiState.amount.toLongOrNull()
        if (amount == null || amount <= 0) {
            uiState = uiState.copy(problem = WavelengthReceiveProblem.NeedsAmount)
            return
        }
        val balance = open.engine.snapshot.value.balance
        val held = (balance?.confirmedSat ?: 0) + (balance?.pendingInSat ?: 0)
        if (WavelengthWalletRollout.wouldExceedCap(open.network, Satoshi(held), Satoshi(amount))) {
            uiState = uiState.copy(problem = WavelengthReceiveProblem.AboveCap)
            return
        }
        uiState = uiState.copy(working = true, problem = null, invoice = null, invoiceIsCredit = false)
        viewModelScope.launch {
            uiState = try {
                val result = open.engine.receive(ReceiveRequest(amountSat = amount, timeoutSeconds = RECEIVE_TIMEOUT_SECONDS))
                uiState.copy(working = false, invoice = result.invoice, invoiceIsCredit = result.entry.counterparty == CREDIT_COUNTERPARTY)
            } catch (e: WavelengthException.ReceiveOutcomeUncertain) {
                val existing = reconcile(open.engine, amount)
                uiState.copy(working = false, invoice = existing, problem = if (existing == null) WavelengthReceiveProblem.Uncertain else null)
            } catch (e: WavelengthException.AmountExceedsVtxoLimit) {
                uiState.copy(working = false, problem = WavelengthReceiveProblem.AboveCoinLimit)
            } catch (e: WavelengthException.BalanceLimitExceeded) {
                uiState.copy(working = false, problem = WavelengthReceiveProblem.AboveBalanceLimit)
            } catch (e: WavelengthException) {
                uiState.copy(working = false, problem = WavelengthReceiveProblem.CouldNotMake)
            }
        }
    }

    /** Asks for an address to board onto Ark from. */
    fun makeAddress() {
        val open = open ?: return
        uiState = uiState.copy(working = true, problem = null)
        viewModelScope.launch {
            uiState = try {
                uiState.copy(working = false, address = open.engine.deposit().address)
            } catch (e: WavelengthException) {
                uiState.copy(working = false, problem = WavelengthReceiveProblem.CouldNotMake)
            }
        }
    }

    /** The invoice a timed-out request may have left behind: one pending, for this amount, that names itself. */
    private suspend fun reconcile(engine: WalletEngine, amount: Long): String? = try {
        engine.refresh()
        engine.snapshot.value.activity
            .firstOrNull { it.kind == EntryKind.RECEIVE && it.status == EntryStatus.PENDING && it.amountSat == amount }
            ?.request?.lightningInvoice?.ifEmpty { null }
    } catch (e: WavelengthException) {
        null
    }

    companion object {
        /** Past the daemon's 20 s default: a receive over a slow connection is still worth waiting for. */
        private const val RECEIVE_TIMEOUT_SECONDS = 60L

        /** What the daemon names as the counterparty of a payment that lands as credit. */
        private const val CREDIT_COUNTERPARTY = "credit"

        fun factory(identityWavelength: IdentityWavelength): ViewModelProvider.Factory = viewModelFactory {
            initializer { WavelengthReceiveViewModel(identityWavelength.state) }
        }
    }
}
