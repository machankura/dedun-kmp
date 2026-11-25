package com.machankura.dedun.compose.ui.view.model

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.wallet.ContactAmendmentEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.DepositRepository
import com.machankura.dedun.compose.ui.view.state.ContactUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One person outside the group that it has agreed to count: who they are, what they have put in,
 * and what any member can ask the group to change. `docs/non-member-deposits.md`, Phase 4.
 */
class ContactViewModel(
    val chatRoomId: String,
    val contactId: HexKey,
    val chain: Chain,
    initialUIState: ContactUIState,
    private val chatRepository: ChatRepository,
    private val depositRepository: DepositRepository,
) : ViewModel() {
    var uiState: ContactUIState by mutableStateOf(initialUIState)
        private set

    val isActionPending: MutableState<Boolean> = mutableStateOf(false)

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)
                ?: return@launch run { uiState = ContactUIState.Error("Couldn't find the group") }
            val contact = depositRepository.contacts(chatRoomId)[contactId.lowercase()]
                ?: return@launch run { uiState = ContactUIState.Error("The group has no record of this contact") }
            val tallies = depositRepository.tallies(chatRoomId, chain)
            // Claimed, their tally is their member's; unclaimed, it is theirs.
            val tally = contact.claimedBy?.let { tallies.members[it] } ?: tallies.contacts[contact.id]
            uiState = ContactUIState.Loaded(
                localChatRoom = localChatRoom,
                contact = contact,
                tally = tally,
                deposits = depositRepository.depositsOf(chatRoomId, chain, contact.id),
                destination = depositRepository.contactDestination(chatRoomId, chain, contact.id),
                names = localChatRoom.localParticipants.associate { participant ->
                    participant.participant.participantPublicKey.lowercase() to
                        (participant.profile?.humanReadableNameOrPubkey() ?: participant.participant.participantPublicKey.take(8))
                },
                members = localChatRoom.localParticipants.map { it.participant.participantPublicKey.lowercase() },
            )
        }
    }

    /** Proposes, or asks a share-holder to propose, [change] to this contact. */
    fun amend(change: ContactAmendmentEvent.Change, onProposed: (sessionId: String) -> Unit, onAsked: () -> Unit, onFailure: () -> Unit) =
        act({ depositRepository.amendContact(it, contactId, change) }, onProposed, onAsked, onFailure)

    /** Proposes, or asks for, the group saying this contact was [member], or nobody in the group (D8). */
    fun claim(member: HexKey?, onProposed: (sessionId: String) -> Unit, onAsked: () -> Unit, onFailure: () -> Unit) =
        act({ depositRepository.claimContact(it, contactId, member) }, onProposed, onAsked, onFailure)

    internal fun act(
        action: suspend (LocalChatRoom) -> DepositRepository.ContactOutcome?,
        onProposed: (sessionId: String) -> Unit,
        onAsked: () -> Unit,
        onFailure: () -> Unit,
    ) {
        val loaded = uiState as? ContactUIState.Loaded ?: return
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
        }
    }

    companion object {
        fun factory(
            chatRoomId: String,
            contactId: HexKey,
            chain: Chain,
            initialUIState: ContactUIState = ContactUIState.Loading,
            chatRepository: ChatRepository,
            depositRepository: DepositRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ContactViewModel(chatRoomId, contactId, chain, initialUIState, chatRepository, depositRepository) }
        }
    }
}
