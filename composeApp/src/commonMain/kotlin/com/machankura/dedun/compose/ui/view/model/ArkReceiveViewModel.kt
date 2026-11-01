package com.machankura.dedun.compose.ui.view.model

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.machankura.dedun.compose.text.SatsInput
import com.machankura.dedun.compose.text.parseSats
import com.machankura.dedun.compose.wallet.ark.ArkDirection
import com.machankura.dedun.compose.wallet.ark.ArkError
import com.machankura.dedun.compose.wallet.ark.ArkEvent
import com.machankura.dedun.compose.wallet.ark.ArkFee
import com.machankura.dedun.compose.wallet.ark.ArkMovement
import com.machankura.dedun.compose.wallet.ark.ArkMovementStatus
import com.machankura.dedun.compose.wallet.ark.ArkReceiveRequest
import com.machankura.dedun.compose.wallet.ark.ArkWallet
import com.machankura.dedun.compose.wallet.ark.ArkWalletRollout
import com.machankura.dedun.compose.wallet.ark.ArkWalletState
import com.machankura.dedun.compose.wallet.ark.IdentityArk
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** What the receive screen draws -- `docs/bark-wallet.md`, Phase 5. */
sealed interface ArkReceiveUIState {
    /** The wallet is not open: the screen says so and offers nothing. */
    data object NoWallet : ArkReceiveUIState

    data object Loading : ArkReceiveUIState

    data class Ready(
        val request: ArkReceiveRequest,
        /** The Lightning fee, once an amount has been asked for. */
        val lightningFee: ArkFee?,
        /** The incoming movement that settled this request, once one has. */
        val received: ArkMovement?,
    ) : ArkReceiveUIState

    data class Failed(val error: ArkError) : ArkReceiveUIState
}

/**
 * One request to be paid: an Ark address and an onchain address at once, and with an
 * amount a BOLT11 invoice as well, in one BIP321 URI.
 *
 * On mainnet the amount asked for is held to the cap -- D5 -- before any invoice is
 * made. An Ark or onchain payment of any size can still arrive: only what this screen
 * asks for can be bounded.
 */
class ArkReceiveViewModel(
    private val walletState: StateFlow<ArkWalletState>,
) : ViewModel() {
    val amountField = TextFieldState()
    val descriptionField = TextFieldState()

    var uiState: ArkReceiveUIState by mutableStateOf(ArkReceiveUIState.Loading)
        private set

    /** Set when the amount asked for is over the cap: the field shows it. */
    var aboveCap: Boolean by mutableStateOf(false)
        private set

    var isActionPending: Boolean by mutableStateOf(false)
        private set

    private var wallet: ArkWallet? = null

    init {
        viewModelScope.launch {
            walletState.collectLatest { state ->
                val open = (state as? ArkWalletState.Open)?.wallet
                wallet = open
                if (open == null) {
                    uiState = ArkReceiveUIState.NoWallet
                    return@collectLatest
                }
                coroutineScope {
                    make(open, amount = null, fee = null)
                    launch { open.events.collect(::settle) }
                }
            }
        }
    }

    fun amount(): SatsInput = parseSats(amountField.text)

    /** A request for the amount typed, or for none if the field is empty. */
    fun request() {
        val open = wallet ?: return
        val amount = (amount() as? SatsInput.Sats)?.amount
        if (amount() !is SatsInput.Sats && amount() != SatsInput.Empty) return
        viewModelScope.launch {
            isActionPending = true
            try {
                if (amount != null && ArkWalletRollout.wouldExceedCap(open.network, open.balance().let { it.spendable + it.pending }, amount)) {
                    aboveCap = true
                    return@launch
                }
                aboveCap = false
                val fee = amount?.let { open.lightningReceiveFee(it) }
                make(open, amount, fee)
            } catch (e: ArkError) {
                uiState = ArkReceiveUIState.Failed(e)
            } finally {
                isActionPending = false
            }
        }
    }

    private suspend fun make(open: ArkWallet, amount: fr.acinq.bitcoin.Satoshi?, fee: ArkFee?) {
        uiState = try {
            val description = descriptionField.text.toString().trim().ifEmpty { null }
            ArkReceiveUIState.Ready(open.receive(amount, description), fee, received = null)
        } catch (e: ArkError) {
            ArkReceiveUIState.Failed(e)
        }
    }

    /**
     * A payment settles the request it answers: a Lightning one by its payment hash,
     * any other by being an incoming payment that succeeded while the request was shown.
     */
    private fun settle(event: ArkEvent) {
        val ready = uiState as? ArkReceiveUIState.Ready ?: return
        val movement = (event as? ArkEvent.MovementChanged)?.movement ?: return
        if (movement.direction != ArkDirection.Incoming || movement.status != ArkMovementStatus.Succeeded) return
        val hash = ready.request.paymentHash
        if (movement.paymentHash != null && hash != null && movement.paymentHash != hash) return
        uiState = ready.copy(received = movement)
    }

    companion object {
        fun factory(identityArk: IdentityArk): ViewModelProvider.Factory = viewModelFactory {
            initializer { ArkReceiveViewModel(identityArk.state) }
        }
    }
}
