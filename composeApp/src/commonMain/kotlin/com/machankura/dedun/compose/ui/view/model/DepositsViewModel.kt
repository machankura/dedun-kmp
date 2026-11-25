package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.wallet.ContactAmendmentEvent
import com.machankura.dedun.compose.managers.Contacts
import com.machankura.dedun.compose.contacts.ContactIdentifier
import com.machankura.dedun.compose.contacts.Party
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.DepositRepository
import com.machankura.dedun.compose.ui.view.state.DepositsUIState
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vitorpamplona.quartz.nip01Core.core.HexKey

/**
 * Every deposit into the group and whose it is, with each member's tally: what the group page's
 * section opens. `docs/member-deposits.md`, Phase 8.
 */
class DepositsViewModel(
    val chatRoomId: String,
    val chain: Chain,
    initialUIState: DepositsUIState,
    private val chatRepository: ChatRepository,
    private val depositRepository: DepositRepository,
) : ViewModel() {
    var uiState: DepositsUIState by mutableStateOf(initialUIState)
        private set

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
                ?: return@launch run { uiState = DepositsUIState.Error("Couldn't find the group") }
            val names = localChatRoom.localParticipants.associate { participant ->
                participant.participant.participantPublicKey.lowercase() to
                    (participant.profile?.humanReadableNameOrPubkey() ?: participant.participant.participantPublicKey.take(8))
            }
            val canSign = localChatRoom.chatRoom.mlsGroupState != null && chatRepository.canSign(chatRoomId)
            uiState = DepositsUIState.Loaded(
                localChatRoom = localChatRoom,
                deposits = depositRepository.deposits(chatRoomId, chain),
                tallies = depositRepository.tallies(chatRoomId, chain),
                names = names,
                canSign = canSign,
                depositors = depositRepository.depositors(chatRoomId, chain).keys.sorted(),
                contacts = depositRepository.contacts(chatRoomId),
                self = localChatRoom.chatRoom.userPublicKey.lowercase(),
                waitingFor = depositRepository.contactsWaitingFor(chatRoomId),
                requests = if (canSign) depositRepository.pendingRequests(chatRoomId) else emptyList(),
            )
        }
    }

    /**
     * Asks the group to sign that [depositKey] is [member]'s, or the group's own when null: a signing
     * session over one attribution. `docs/member-deposits.md`, Phase 9.
     */
    fun attribute(depositKey: String, party: Party?, onSuccess: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        val loaded = uiState as? DepositsUIState.Loaded ?: return
        if (isActionPending.value) return
        isActionPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val session = runCatching { depositRepository.attribute(loaded.localChatRoom, chain, mapOf(depositKey to party)) }.getOrNull()
            withContext(Dispatchers.Main) {
                isActionPending.value = false
                if (session != null) onSuccess(session.id) else onFailure()
            }
        }
    }

    /**
     * Proposes the contact [identifier] called [name], or asks a share-holder to (`docs/non-member-deposits.md`,
     * Phase 3). [onProposed] opens the signing session; [onAsked] says a request went out.
     */
    fun addContact(identifier: ContactIdentifier, name: String, onProposed: (sessionId: String) -> Unit, onAsked: () -> Unit, onFailure: () -> Unit) =
        contactAction({ depositRepository.addContact(it, identifier, name) }, onProposed, onAsked, onFailure)

    /** Proposes, or asks for, [change] to [contactId]. */
    fun amendContact(contactId: HexKey, change: ContactAmendmentEvent.Change, onProposed: (sessionId: String) -> Unit, onAsked: () -> Unit, onFailure: () -> Unit) =
        contactAction({ depositRepository.amendContact(it, contactId, change) }, onProposed, onAsked, onFailure)

    private fun contactAction(
        action: suspend (LocalChatRoom) -> DepositRepository.ContactOutcome?,
        onProposed: (sessionId: String) -> Unit,
        onAsked: () -> Unit,
        onFailure: () -> Unit,
    ) {
        val loaded = uiState as? DepositsUIState.Loaded ?: return
        if (isActionPending.value) return
        isActionPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = action(loaded.localChatRoom)
            withContext(Dispatchers.Main) {
                isActionPending.value = false
                when (outcome) {
                    is DepositRepository.ContactOutcome.Proposed -> onProposed(outcome.session.id)
                    DepositRepository.ContactOutcome.Asked -> onAsked()
                    null -> onFailure()
                }
            }
            if (outcome is DepositRepository.ContactOutcome.Asked) initiate()
        }
    }

    /** Asks a share-holder to give [depositKey], one of this member's own, to [party]. */
    fun askToAttribute(depositKey: String, party: Party?, onAsked: () -> Unit, onFailure: () -> Unit) {
        if (isActionPending.value) return
        isActionPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val asked = depositRepository.askToAttribute(chatRoomId, chain, depositKey, party)
            withContext(Dispatchers.Main) {
                isActionPending.value = false
                if (asked) onAsked() else onFailure()
            }
        }
    }

    /** Puts a member's [request] to the group, from this device's share. */
    fun putToGroup(request: Contacts.PendingRequest, onSuccess: (sessionId: String) -> Unit, onFailure: () -> Unit) {
        val loaded = uiState as? DepositsUIState.Loaded ?: return
        if (isActionPending.value) return
        isActionPending.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val session = depositRepository.putToGroup(loaded.localChatRoom, request)
            withContext(Dispatchers.Main) {
                isActionPending.value = false
                if (session != null) onSuccess(session.id) else onFailure()
            }
        }
    }

    companion object {
        fun factory(
            chatRoomId: String,
            chain: Chain,
            initialUIState: DepositsUIState = DepositsUIState.Loading,
            chatRepository: ChatRepository,
            depositRepository: DepositRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { DepositsViewModel(chatRoomId, chain, initialUIState, chatRepository, depositRepository) }
        }
    }
}
