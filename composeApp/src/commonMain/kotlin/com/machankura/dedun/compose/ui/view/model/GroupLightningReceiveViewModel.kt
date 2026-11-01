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
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupLightningRepository
import com.machankura.dedun.compose.text.SatsInput
import com.machankura.dedun.compose.text.parseSats
import com.machankura.dedun.compose.ui.view.state.GroupLightningReceiveUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Asks the operator's node for an invoice and waits for it. Any member may ask; the invoice arrives
 * only once the inbound path has checked that it pays the node the group signed for.
 */
class GroupLightningReceiveViewModel(
    val activeUserPublicKey: HexKey,
    val chatRoomId: String,
    val chain: Chain,
    initialUIState: GroupLightningReceiveUIState,
    private val chatRepository: ChatRepository,
    private val lightningRepository: GroupLightningRepository,
) : ViewModel() {
    private val logger = Logger.withTag(TAG)

    var uiState: GroupLightningReceiveUIState by mutableStateOf(initialUIState)
        private set

    val amountField = TextFieldState()
    val descriptionField = TextFieldState()
    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
                ?: return@launch run { uiState = GroupLightningReceiveUIState.Error("Couldn't find the group") }
            val wallet = lightningRepository.lightningWallet(chatRoomId, chain)
            uiState = if (wallet == null || lightningRepository.operator(chatRoomId, chain, wallet.statement.wallet)?.publicKey == null) GroupLightningReceiveUIState.Unavailable
            else GroupLightningReceiveUIState.Form(localChatRoom, wallet, lightningRepository.observeReport(chatRoomId, chain, wallet.statement.wallet).first()?.snapshot?.offer)
        }
    }

    /** The amount typed, in sats; null for none, and [SatsInput] problems as they are. */
    fun amount(): SatsInput = parseSats(amountField.text)

    fun request(onFailure: () -> Unit) {
        val form = uiState as? GroupLightningReceiveUIState.Form ?: return
        val amountMsat = when (val amount = amount()) {
            SatsInput.Empty -> null
            is SatsInput.Sats -> amount.amount.sat * 1000
            else -> return onFailure()
        }
        if (isActionPending.value) return
        isActionPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val requestId = runCatching {
                lightningRepository.requestInvoice(form.localChatRoom, activeUserPublicKey, form.wallet, amountMsat, descriptionField.text.toString().trim())
            }.onFailure { logger.e("Invoice request in $chatRoomId failed", it) }.getOrNull()
            isActionPending.value = false
            if (requestId == null) return@launch withContext(Dispatchers.Main) { onFailure() }
            uiState = GroupLightningReceiveUIState.Waiting(requestId)
            val invoice = lightningRepository.observeInvoice(chatRoomId, chain, form.wallet.statement.wallet, requestId).filterNotNull().first()
            uiState = GroupLightningReceiveUIState.Invoice(invoice.bolt11)
        }
    }

    companion object {
        private const val TAG = "GroupLightningReceiveViewModel"

        fun factory(
            activeUserPublicKey: HexKey,
            chatRoomId: String,
            chain: Chain,
            chatRepository: ChatRepository,
            lightningRepository: GroupLightningRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GroupLightningReceiveViewModel(activeUserPublicKey, chatRoomId, chain, GroupLightningReceiveUIState.Loading, chatRepository, lightningRepository)
            }
        }
    }
}
