package com.machankura.dedun.compose.ui.view.model

import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.machankura.dedun.compose.repository.DepositRepository
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
import com.machankura.dedun.compose.database.model.GroupLightningInvoiceRow
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupLightningRepository
import com.machankura.dedun.compose.text.SatsInput
import com.machankura.dedun.compose.text.parseSats
import com.machankura.dedun.compose.ui.view.state.GroupLightningReceiveUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import fr.acinq.lightning.MilliSatoshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
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
    /** Whether this profile is a depositor: only a depositor is issued an invoice (`docs/member-deposits.md`). */
    private val depositRepository: DepositRepository = DepositRepository.NO_OP,
) : ViewModel() {
    private val logger = Logger.withTag(TAG)

    var uiState: GroupLightningReceiveUIState by mutableStateOf(initialUIState)
        private set

    val amountField = TextFieldState()
    val descriptionField = TextFieldState()
    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    /** Watches the one invoice on screen for its payment; cancelled as soon as something else is. */
    private var watching: Job? = null

    fun initiate() {
        watching?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
                ?: return@launch run { uiState = GroupLightningReceiveUIState.Error("Couldn't find the group") }
            val wallet = lightningRepository.lightningWallet(chatRoomId, chain)
            if (wallet == null || lightningRepository.operator(chatRoomId, chain, wallet.statement.wallet)?.publicKey == null) {
                uiState = GroupLightningReceiveUIState.Unavailable
                return@launch
            }
            val canPropose = localChatRoom.chatRoom.mlsGroupState != null && chatRepository.canSign(chatRoomId)
            val standing = depositRepository.standing(chatRoomId, wallet.statement.chain, canPropose)
            if (standing !is DepositRepository.Standing.Open) {
                uiState = GroupLightningReceiveUIState.NotDepositor(localChatRoom, standing)
                return@launch
            }
            // Whose deposit it is, where the payer will read it: on their own wallet's screen.
            val me = nameOf(localChatRoom, activeUserPublicKey)
            val group = localChatRoom.chatRoom.subject?.takeIf { it.isNotBlank() }
            withContext(Dispatchers.Main) {
                if (descriptionField.text.isEmpty()) {
                    descriptionField.setTextAndPlaceCursorAtEnd(if (group != null) "Deposit from @$me to $group" else "Deposit from @$me")
                }
            }
            uiState = GroupLightningReceiveUIState.Form(localChatRoom, wallet)
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
            val invoice = withTimeoutOrNull(REPLY_WAIT) {
                lightningRepository.observeInvoice(chatRoomId, chain, form.wallet.statement.wallet, requestId).filterNotNull().first()
            }
            if (invoice == null) {
                uiState = GroupLightningReceiveUIState.NoReply(requestId)
                return@launch
            }
            uiState = GroupLightningReceiveUIState.Invoice(invoice.bolt11)
            watchForPayment(form.localChatRoom, form.wallet.statement.wallet, invoice)
        }
    }

    /**
     * Takes the invoice off the screen once it is paid (`docs/paid-requests.md`).
     *
     * [GroupLightningInvoiceRow.paymentHash] is what joins a payment to the invoice
     * (`docs/member-deposits.md`, Phase 6). A row written before deposits were attributed has
     * none, so nothing is watched and the screen behaves as it did before -- which is the
     * honest outcome, since without a hash there is no way to tell this member's payment from
     * anybody else's.
     */
    private fun watchForPayment(localChatRoom: LocalChatRoom, wallet: Long, invoice: GroupLightningInvoiceRow) {
        watching?.cancel()
        val hash = invoice.paymentHash ?: return
        watching = viewModelScope.launch(Dispatchers.IO) {
            val paid = lightningRepository.observePayments(chatRoomId, chain, wallet)
                .mapNotNull { rows -> rows.firstOrNull { it.incoming && it.succeeded && it.paymentHash.equals(hash, ignoreCase = true) } }
                .first()
            // Still this invoice: asking again while a payment lands would otherwise replace
            // the new form with the old invoice's receipt.
            val showing = uiState as? GroupLightningReceiveUIState.Invoice ?: return@launch
            if (showing.bolt11 != invoice.bolt11) return@launch
            val operator = lightningRepository.operator(chatRoomId, chain, wallet)?.publicKey
            uiState = GroupLightningReceiveUIState.Paid(
                bolt11 = invoice.bolt11,
                amount = MilliSatoshi(paid.amountMsat).truncateToSatoshi(),
                onTheWordOf = operator?.let { nameOf(localChatRoom, it) },
            )
        }
    }

    /**
     * Back to the form -- after no reply, after a payment, or after the invoice expired, to be
     * paid again. From anything else it does nothing: the screen offers it in those three places
     * only, and a stray call must not throw away an invoice somebody may be about to pay.
     */
    fun askAgain() {
        if (uiState is GroupLightningReceiveUIState.NoReply ||
            uiState is GroupLightningReceiveUIState.Paid ||
            uiState is GroupLightningReceiveUIState.Invoice
        ) {
            initiate()
        }
    }

    /** How a member of this room is named on screen, falling back to the head of their key. */
    private fun nameOf(localChatRoom: LocalChatRoom, publicKey: HexKey): String =
        localChatRoom.localParticipants.firstOrNull { it.participant.participantPublicKey.equals(publicKey, ignoreCase = true) }
            ?.profile?.humanReadableNameOrPubkey() ?: publicKey.take(8)

    /** Asks the group to open this profile's deposits, from the step before the form. */
    fun openMyDeposits(onSuccess: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        val state = uiState as? GroupLightningReceiveUIState.NotDepositor ?: return
        if (isActionPending.value) return
        isActionPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val session = runCatching { depositRepository.open(state.localChatRoom, chain, activeUserPublicKey) }
                .onFailure { logger.e("Couldn't propose opening deposits in $chatRoomId", it) }.getOrNull()
            withContext(Dispatchers.Main) {
                isActionPending.value = false
                if (session != null) onSuccess(session.id) else onFailure()
            }
        }
    }

    companion object {
        private const val TAG = "GroupLightningReceiveViewModel"

        /** How long the screen waits for the operator's device to answer before saying it hasn't. */
        val REPLY_WAIT: Duration = 60.seconds

        fun factory(
            activeUserPublicKey: HexKey,
            chatRoomId: String,
            chain: Chain,
            chatRepository: ChatRepository,
            lightningRepository: GroupLightningRepository,
            depositRepository: DepositRepository = DepositRepository.NO_OP,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GroupLightningReceiveViewModel(activeUserPublicKey, chatRoomId, chain, GroupLightningReceiveUIState.Loading, chatRepository, lightningRepository, depositRepository)
            }
        }
    }
}
