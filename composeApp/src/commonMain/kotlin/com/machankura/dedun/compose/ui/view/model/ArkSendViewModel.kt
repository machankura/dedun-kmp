package com.machankura.dedun.compose.ui.view.model

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.machankura.dedun.compose.text.SatsInput
import com.machankura.dedun.compose.text.grouped
import com.machankura.dedun.compose.text.parseSats
import com.machankura.dedun.compose.wallet.ark.ArkError
import com.machankura.dedun.compose.wallet.ark.ArkFee
import com.machankura.dedun.compose.wallet.ark.ArkPaymentMethod
import com.machankura.dedun.compose.wallet.ark.ArkPaymentOption
import com.machankura.dedun.compose.wallet.ark.ArkPaymentRequest
import com.machankura.dedun.compose.wallet.ark.ArkSendResult
import com.machankura.dedun.compose.wallet.ark.ArkWallet
import com.machankura.dedun.compose.wallet.ark.ArkWalletRollout
import com.machankura.dedun.compose.wallet.ark.ArkWalletState
import com.machankura.dedun.compose.wallet.ark.IdentityArk
import fr.acinq.bitcoin.Satoshi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Why a payment cannot go ahead as it stands. */
enum class ArkSendProblem { NothingToPay, AmountNeeded, AboveCap, NotEnough, NoFee }

/** The send screen's steps -- `docs/bark-wallet.md`, Phase 6. */
sealed interface ArkSendUIState {
    data object NoWallet : ArkSendUIState

    /** The paste field, and why the last text could not be paid, if it could not. */
    data class Entering(val problem: ArkSendProblem? = null) : ArkSendUIState

    data object Working : ArkSendUIState

    /** A parsed request: the option it will pay, and its fee once there is an amount. */
    data class Review(
        val request: ArkPaymentRequest,
        val option: ArkPaymentOption,
        val amount: Satoshi?,
        val fee: ArkFee?,
        val spendable: Satoshi,
        val problem: ArkSendProblem?,
    ) : ArkSendUIState {
        val canSend: Boolean get() = problem == null && fee != null && amount != null
    }

    data class Sent(val result: ArkSendResult) : ArkSendUIState

    data class Failed(val error: ArkError) : ArkSendUIState
}

/**
 * Paying from the profile's Ark wallet: one field takes whatever was pasted, Bark decides
 * the rail, and the review names it, because the fee and the time to settle differ by rail.
 * On mainnet a send is held to the cap -- D5 -- before it is estimated, and again before it
 * is sent.
 */
class ArkSendViewModel(
    private val walletState: StateFlow<ArkWalletState>,
) : ViewModel() {
    val requestField = TextFieldState()
    val amountField = TextFieldState()
    val commentField = TextFieldState()

    var uiState: ArkSendUIState by mutableStateOf(
        if (walletState.value is ArkWalletState.Open) ArkSendUIState.Entering() else ArkSendUIState.NoWallet
    )
        private set

    private val wallet: ArkWallet? get() = (walletState.value as? ArkWalletState.Open)?.wallet

    fun amount(): SatsInput = parseSats(amountField.text)

    /** Reads the pasted text into a request, and estimates at once if it names an amount. */
    fun parse() {
        val open = wallet ?: return
        val text = requestField.text.toString().trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            uiState = ArkSendUIState.Working
            val request = try {
                open.parse(text)
            } catch (e: ArkError) {
                uiState = ArkSendUIState.Entering(ArkSendProblem.NothingToPay)
                return@launch
            }
            val option = request.chosen
            if (option == null) {
                uiState = ArkSendUIState.Entering(ArkSendProblem.NothingToPay)
                return@launch
            }
            request.amount?.let { amountField.setTextAndPlaceCursorAtEnd(it.grouped()) }
            review(open, request, option)
        }
    }

    /** The amount field changed: estimate again. */
    fun reestimate() {
        val open = wallet ?: return
        val review = uiState as? ArkSendUIState.Review ?: return
        viewModelScope.launch { review(open, review.request, review.option) }
    }

    /** Back to the paste field. */
    fun change() {
        uiState = ArkSendUIState.Entering()
    }

    fun send() {
        val open = wallet ?: return
        val review = uiState as? ArkSendUIState.Review ?: return
        if (!review.canSend) return
        val amount = review.amount ?: return
        // The cap again, at the moment of sending: the estimate may be from before a change.
        if (ArkWalletRollout.sendExceedsCap(open.network, amount)) {
            uiState = review.copy(problem = ArkSendProblem.AboveCap)
            return
        }
        viewModelScope.launch {
            uiState = ArkSendUIState.Working
            uiState = try {
                val comment = commentField.text.toString().trim().ifEmpty { null }
                // An invoice that names its amount is paid without one: Bark refuses a second.
                val sendAmount = if (review.option.requiresAmount) amount else null
                ArkSendUIState.Sent(open.send(review.option.method, sendAmount, comment))
            } catch (e: ArkError) {
                ArkSendUIState.Failed(e)
            }
        }
    }

    private suspend fun review(open: ArkWallet, request: ArkPaymentRequest, option: ArkPaymentOption) {
        val amount = request.amount ?: (amount() as? SatsInput.Sats)?.amount
        val spendable = try { open.balance().spendable } catch (e: ArkError) { Satoshi(0) }
        val base = ArkSendUIState.Review(request, option, amount, fee = null, spendable = spendable, problem = null)
        uiState = when {
            amount == null -> base.copy(problem = ArkSendProblem.AmountNeeded)
            ArkWalletRollout.sendExceedsCap(open.network, amount) -> base.copy(problem = ArkSendProblem.AboveCap)
            else -> try {
                val fee = open.estimateFee(option.method, amount)
                base.copy(fee = fee, problem = ArkSendProblem.NotEnough.takeIf { fee.gross > spendable })
            } catch (e: ArkError.InsufficientFunds) {
                base.copy(problem = ArkSendProblem.NotEnough)
            } catch (e: ArkError) {
                base.copy(problem = ArkSendProblem.NoFee)
            }
        }
    }

    companion object {
        fun factory(identityArk: IdentityArk): ViewModelProvider.Factory = viewModelFactory {
            initializer { ArkSendViewModel(identityArk.state) }
        }

        /** Whether a method takes a note for the payee: only a Lightning address and LNURL carry one. */
        fun takesComment(method: ArkPaymentMethod): Boolean =
            method is ArkPaymentMethod.LightningAddress || method is ArkPaymentMethod.Lnurl
    }
}
