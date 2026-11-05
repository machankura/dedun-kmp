package com.machankura.dedun.compose.ui.view.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.machankura.dedun.compose.network.LightningAddressResolver
import com.machankura.dedun.compose.text.LightningAddress
import com.machankura.dedun.compose.wallet.wavelength.IdentityWavelength
import com.machankura.dedun.compose.wallet.wavelength.WalletState
import com.machankura.dedun.compose.wallet.wavelength.WavelengthWalletRollout
import engineering.lightning.wavelength.Destination
import engineering.lightning.wavelength.InvoiceAmount
import engineering.lightning.wavelength.Network
import engineering.lightning.wavelength.SendRequest
import engineering.lightning.wavelength.WavelengthException
import engineering.lightning.wavelength.model.PrepareSendResult
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.MilliSatoshi
import fr.acinq.lightning.utils.toMilliSatoshi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Why a payment can't go ahead. */
enum class WavelengthSendProblem {
    /** Nothing here this wallet can pay. */
    NotPayable,

    /** A kind of request the daemon doesn't pay, and shouldn't look like a typing mistake. */
    Unsupported,

    /** An invoice whose amount can't be held in whole sats. */
    Unrepresentable,

    NeedsAmount,

    /** Over the cap this app sets on mainnet (D5). */
    AboveCap,

    /** More than the operator takes in one coin. */
    AboveCoinLimit,

    /** Not enough in the wallet. */
    NotEnough,

    /** The Lightning address did not give an invoice. */
    AddressFailed,

    /** The quote went stale between the review and the send. A new one is needed. */
    QuoteExpired,

    CouldNotSend,
}

/** What the send screen draws -- `docs/wavelength-wallet.md`, Phase 7. */
sealed interface WavelengthSendUIState {
    data object NoWallet : WavelengthSendUIState

    data class Entering(
        val destination: String = "",
        val amount: String = "",
        /** Whether the destination names no amount of its own. */
        val needsAmount: Boolean = false,
        val problem: WavelengthSendProblem? = null,
    ) : WavelengthSendUIState

    data object Working : WavelengthSendUIState

    data class Reviewing(val quote: PrepareSendResult, val remainingSat: Long, val network: Network) : WavelengthSendUIState

    data object Sent : WavelengthSendUIState
}

/**
 * Paying. Paste, quote, review, send: the fee is always shown before anything moves, because the
 * quote is the only thing the daemon promises. A quote is single-use, so any failure goes back to
 * the form and quotes again rather than trying it twice.
 */
class WavelengthSendViewModel(
    private val walletState: StateFlow<WalletState>,
    /** An invoice for [amount] from a Lightning address, or null: the resolver, in the app. */
    private val invoiceForAddress: suspend (LightningAddress, Satoshi, Network) -> String?,
) : ViewModel() {
    var uiState: WavelengthSendUIState by mutableStateOf(
        if (walletState.value is WalletState.Open) WavelengthSendUIState.Entering() else WavelengthSendUIState.NoWallet,
    )
        private set

    private val open: WalletState.Open? get() = walletState.value as? WalletState.Open
    private var entering = WavelengthSendUIState.Entering()

    fun onDestinationChange(text: String) {
        entering = entering.copy(destination = text, needsAmount = needsAmount(text), problem = null)
        uiState = entering
    }

    fun onAmountChange(text: String) {
        entering = entering.copy(amount = text.filter(Char::isDigit), problem = null)
        uiState = entering
    }

    /** Back from the review, keeping what was typed. */
    fun change() {
        uiState = entering
    }

    fun review() {
        val open = open ?: return
        val typed = entering.destination.trim()
        if (typed.isEmpty()) return fail(WavelengthSendProblem.NotPayable)
        val amount = entering.amount.toLongOrNull()?.takeIf { it > 0 }
        uiState = WavelengthSendUIState.Working
        viewModelScope.launch {
            try {
                val request = requestFor(typed, amount, open.network) ?: return@launch
                val quote = open.engine.prepareSend(request)
                if (WavelengthWalletRollout.sendExceedsCap(open.network, Satoshi(quote.amountSat))) return@launch fail(WavelengthSendProblem.AboveCap)
                val spendable = open.engine.snapshot.value.balance?.confirmedSat ?: 0
                val outflow = if (quote.totalOutflowKnown) quote.expectedTotalOutflowSat else quote.amountSat
                uiState = WavelengthSendUIState.Reviewing(quote, remainingSat = spendable - outflow, network = open.network)
            } catch (e: WavelengthException) {
                fail(problemFor(e))
            }
        }
    }

    fun send() {
        val open = open ?: return
        val reviewing = uiState as? WavelengthSendUIState.Reviewing ?: return
        uiState = WavelengthSendUIState.Working
        viewModelScope.launch {
            uiState = try {
                open.engine.sendPrepared(reviewing.quote)
                WavelengthSendUIState.Sent
            } catch (e: WavelengthException) {
                entering = entering.copy(problem = problemFor(e))
                entering
            }
        }
    }

    private fun fail(problem: WavelengthSendProblem) {
        entering = entering.copy(problem = problem)
        uiState = entering
    }

    /** The daemon's request for what was typed, or null having said why not. */
    private suspend fun requestFor(typed: String, amount: Long?, network: Network): SendRequest? {
        val address = LightningAddress.parse(typed)
        if (address is LightningAddress.Parsed.Address) {
            if (amount == null) {
                fail(WavelengthSendProblem.NeedsAmount)
                return null
            }
            val invoice = invoiceForAddress(address.address, Satoshi(amount), network)
            if (invoice == null) {
                fail(WavelengthSendProblem.AddressFailed)
                return null
            }
            return SendRequest.Invoice(invoice)
        }
        if (address == LightningAddress.Parsed.Lnurl || address == LightningAddress.Parsed.Offer || address == LightningAddress.Parsed.Bip353) {
            fail(WavelengthSendProblem.Unsupported)
            return null
        }
        return when (val destination = Destination.classify(typed)) {
            Destination.Empty -> null.also { fail(WavelengthSendProblem.NotPayable) }
            is Destination.Invoice -> when (destination.amount) {
                is InvoiceAmount.Known -> SendRequest.Invoice(typed)
                InvoiceAmount.Amountless ->
                    if (amount == null) null.also { fail(WavelengthSendProblem.NeedsAmount) } else SendRequest.Invoice(typed, amountSat = amount)
                InvoiceAmount.Unrepresentable -> null.also { fail(WavelengthSendProblem.Unrepresentable) }
            }
            Destination.Address ->
                if (amount == null) null.also { fail(WavelengthSendProblem.NeedsAmount) } else SendRequest.Onchain(typed, amountSat = amount)
        }
    }

    private fun needsAmount(text: String): Boolean {
        val typed = text.trim()
        if (typed.isEmpty()) return false
        if (LightningAddress.parse(typed) is LightningAddress.Parsed.Address) return true
        return when (val destination = Destination.classify(typed)) {
            is Destination.Invoice -> destination.amount == InvoiceAmount.Amountless
            Destination.Address -> true
            Destination.Empty -> false
        }
    }

    private fun problemFor(e: WavelengthException): WavelengthSendProblem = when (e) {
        is WavelengthException.InvalidDestination -> WavelengthSendProblem.NotPayable
        is WavelengthException.AmountRequired -> WavelengthSendProblem.NeedsAmount
        is WavelengthException.AmountExceedsVtxoLimit -> WavelengthSendProblem.AboveCoinLimit
        is WavelengthException.BalanceLimitExceeded, is WavelengthException.AmountInvalid -> WavelengthSendProblem.NotEnough
        is WavelengthException.InvalidSendIntent -> WavelengthSendProblem.QuoteExpired
        else -> WavelengthSendProblem.CouldNotSend
    }

    companion object {
        fun factory(identityWavelength: IdentityWavelength, resolver: LightningAddressResolver, torEnabled: () -> Boolean = { false }): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                WavelengthSendViewModel(identityWavelength.state) { address, amount, network ->
                    val chain = if (network == Network.MAINNET) Chain.Mainnet else Chain.Signet
                    val answer = resolver.invoiceFor(address, amount.toMilliSatoshi(), chain, torEnabled())
                    (answer as? LightningAddressResolver.PayoutInvoice.Ready)?.bolt11
                }
            }
        }
    }
}
