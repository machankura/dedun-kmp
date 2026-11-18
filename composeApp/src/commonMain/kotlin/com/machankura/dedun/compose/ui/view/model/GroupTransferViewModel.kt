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
import com.machankura.dedun.compose.nostr.wallet.TransferRequestEvent
import com.machankura.dedun.compose.nostr.wallet.TransferRequestEvent.Rail
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.TransferRepository
import com.machankura.dedun.compose.text.SatsInput
import com.machankura.dedun.compose.text.parseSats
import com.machankura.dedun.compose.ui.view.state.GroupTransferUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A member transferring part of their balance on one rail to another member: whom, how much, and a note, then asking the group
 * to record it. `docs/member-transfers.md`, Phase 4. What the form allows is what the member's own device would sign, so the
 * button never asks the group for something this device would refuse.
 */
class GroupTransferViewModel(
    val chatRoomId: String,
    val chain: Chain,
    val rail: Rail,
    initialUIState: GroupTransferUIState,
    private val chatRepository: ChatRepository,
    private val transferRepository: TransferRepository,
) : ViewModel() {
    var uiState: GroupTransferUIState by mutableStateOf(initialUIState)
        private set

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    val amount = TextFieldState()
    val note = TextFieldState()
    var receiver: HexKey? by mutableStateOf(null)
    var everything: Boolean by mutableStateOf(false)

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
                ?: return@launch run { uiState = GroupTransferUIState.Error("Couldn't find the group") }
            val names = localChatRoom.localParticipants.associate { participant ->
                participant.participant.participantPublicKey.lowercase() to
                    (participant.profile?.humanReadableNameOrPubkey() ?: participant.participant.participantPublicKey.take(8))
            }
            uiState = runCatching {
                GroupTransferUIState.Loaded(
                    localChatRoom = localChatRoom,
                    givable = transferRepository.givable(chatRoomId, chain, rail),
                    receivers = transferRepository.receivers(chatRoomId, chain).sortedWith(compareBy({ !it.canReceive }, { names[it.member]?.lowercase() ?: it.member })),
                    names = names,
                    canAsk = transferRepository.canAsk(),
                )
            }.getOrElse { GroupTransferUIState.Error(it.message ?: "Couldn't read this group's balances") }
        }
    }

    /** What the form says to give, in the rail's ledger sats (T5), or null while it says nothing a transfer could be. */
    fun ledgerAmount(): Long? {
        val loaded = uiState as? GroupTransferUIState.Loaded ?: return null
        if (everything) return loaded.givable.givableSat
        val typed = (parseSats(amount.text) as? SatsInput.Sats)?.amount?.sat ?: return null
        return loaded.givable.ledgerFor(typed)
    }

    sealed interface Problem {
        data object NoReceiver : Problem
        data object NoAmount : Problem
        data object BelowFloor : Problem
        data class OverBalance(val bySat: Long) : Problem
    }

    /** Why the form can't be sent yet, or null when it can. */
    fun problem(): Problem? {
        val loaded = uiState as? GroupTransferUIState.Loaded ?: return Problem.NoAmount
        return problemWith(receiver, ledgerAmount(), loaded.givable.givableSat)
    }

    /** Signs the request, carries it, and puts it to the group where this device can: [onSent] with the session if one opened. */
    fun send(onSent: (sessionId: String?) -> Unit, onFailure: () -> Unit) {
        val loaded = uiState as? GroupTransferUIState.Loaded ?: return
        val to = receiver ?: return
        val sat = ledgerAmount() ?: return
        if (problem() != null || isActionPending.value) return
        isActionPending.value = true
        val written = note.text.toString().trim().takeIf { it.isNotEmpty() }
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = runCatching { transferRepository.request(loaded.localChatRoom, chain, rail, to, sat, written) }
            withContext(Dispatchers.Main) {
                isActionPending.value = false
                outcome.fold(onSuccess = { onSent(it?.id) }, onFailure = { onFailure() })
            }
        }
    }

    companion object {
        /** Why [receiver] and [ledgerSat] can't be a transfer from a member who can give [givableSat], or null when they can. */
        fun problemWith(receiver: HexKey?, ledgerSat: Long?, givableSat: Long): Problem? = when {
            receiver == null -> Problem.NoReceiver
            ledgerSat == null || ledgerSat <= 0 -> Problem.NoAmount
            ledgerSat < TransferRequestEvent.MIN_TRANSFER_SAT -> Problem.BelowFloor
            ledgerSat > givableSat -> Problem.OverBalance(ledgerSat - givableSat)
            else -> null
        }

        fun factory(
            chatRoomId: String,
            chain: Chain,
            rail: Rail,
            initialUIState: GroupTransferUIState = GroupTransferUIState.Loading,
            chatRepository: ChatRepository,
            transferRepository: TransferRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { GroupTransferViewModel(chatRoomId, chain, rail, initialUIState, chatRepository, transferRepository) }
        }
    }
}
