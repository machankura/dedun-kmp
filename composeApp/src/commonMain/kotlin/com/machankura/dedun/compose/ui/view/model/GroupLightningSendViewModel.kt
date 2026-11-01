package com.machankura.dedun.compose.ui.view.model

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.managers.GroupLightningAuthorisations
import com.machankura.dedun.compose.nostr.lightning.GroupLightningAuthorisationEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupLightningRepository
import com.machankura.dedun.compose.text.SatsInput
import com.machankura.dedun.compose.text.parseSats
import com.machankura.dedun.compose.ui.view.state.GroupLightningSendUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import fr.acinq.lightning.payment.Bolt11Invoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A payment from the group's Lightning wallet, as a proposal: the invoice, the amount when the invoice
 * names none, and the most the fee may be. The group signs it like any decision, and the operator's
 * node pays it once it has.
 */
class GroupLightningSendViewModel(
    val activeUserPublicKey: HexKey,
    val chatRoomId: String,
    val chain: Chain,
    initialUIState: GroupLightningSendUIState,
    private val chatRepository: ChatRepository,
    private val lightningRepository: GroupLightningRepository,
) : ViewModel() {
    private val logger = Logger.withTag(TAG)

    var uiState: GroupLightningSendUIState by mutableStateOf(initialUIState)
        private set

    val invoiceField = TextFieldState()
    val amountField = TextFieldState()
    val maxFeeField = TextFieldState()
    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
                ?: return@launch run { uiState = GroupLightningSendUIState.Error("Couldn't find the group") }
            val wallet = lightningRepository.lightningWallet(chatRoomId, chain)
            uiState = if (wallet == null || lightningRepository.operator(chatRoomId, chain, wallet.statement.wallet)?.publicKey == null) GroupLightningSendUIState.Unavailable
            else GroupLightningSendUIState.Form(localChatRoom, wallet)
        }
    }

    fun amount(): SatsInput = parseSats(amountField.text)

    /** The amount the invoice in the field names, or null when it names none or is not an invoice. */
    fun invoiceAmountMsat(): Long? =
        runCatching { Bolt11Invoice.read(invoiceField.text.toString().trim().removePrefix("lightning:").removePrefix("LIGHTNING:")).get().amount?.msat }.getOrNull()
    fun maxFee(): SatsInput = parseSats(maxFeeField.text)

    /** The fee limit when the field is left empty: a percent of [amountSat], and never under ten sats. */
    fun defaultMaxFeeSat(amountSat: Long): Long = maxOf(MIN_FEE_SAT, amountSat / 100)

    /** What the form would propose, or why it cannot; null while the invoice field is empty. */
    fun preview(): Result<GroupLightningAuthorisationEvent.Pay>? {
        val form = uiState as? GroupLightningSendUIState.Form ?: return null
        if (invoiceField.text.isBlank()) return null
        val amountMsat = (amount() as? SatsInput.Sats)?.amount?.sat?.times(1000)
        val first = GroupLightningAuthorisations.prepare(form.wallet, invoiceField.text.toString(), amountMsat, maxFeeMsat = 0)
        val pay = first.getOrElse { return first }
        val maxFeeMsat = when (val fee = maxFee()) {
            SatsInput.Empty -> defaultMaxFeeSat(pay.amountMsat / 1000) * 1000
            is SatsInput.Sats -> fee.amount.sat * 1000
            else -> return null
        }
        return Result.success(pay.copy(maxFeeMsat = maxFeeMsat))
    }

    fun propose(onSession: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        val form = uiState as? GroupLightningSendUIState.Form ?: return
        val pay = preview()?.getOrNull() ?: return onFailure()
        if (isActionPending.value) return
        isActionPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val session = runCatching { lightningRepository.proposePayment(form.localChatRoom, activeUserPublicKey, pay) }
                .onFailure { logger.e("Payment proposal in $chatRoomId failed", it) }.getOrNull()
            isActionPending.value = false
            withContext(Dispatchers.Main) { if (session != null) onSession(session.id) else onFailure() }
        }
    }

    companion object {
        private const val TAG = "GroupLightningSendViewModel"
        const val MIN_FEE_SAT = 10L

        fun factory(
            activeUserPublicKey: HexKey,
            chatRoomId: String,
            chain: Chain,
            chatRepository: ChatRepository,
            lightningRepository: GroupLightningRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GroupLightningSendViewModel(activeUserPublicKey, chatRoomId, chain, GroupLightningSendUIState.Loading, chatRepository, lightningRepository)
            }
        }
    }
}
