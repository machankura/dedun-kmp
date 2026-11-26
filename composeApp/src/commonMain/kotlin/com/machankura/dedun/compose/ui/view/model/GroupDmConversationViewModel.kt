package com.machankura.dedun.compose.ui.view.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.machankura.dedun.compose.managers.GroupDmSender
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupDmRepository
import com.machankura.dedun.compose.ui.view.state.GroupDmConversationUIState

/**
 * One of a group's conversations, watched, and marked read while it is open
 * (docs/group-threshold-ecdh.md, Phase 8). A new conversation is [recipients] with nothing in it.
 */
class GroupDmConversationViewModel(
    val chatRoomId: String,
    val activeUserPublicKey: HexKey,
    val conversationId: HexKey?,
    val recipients: List<HexKey>,
    val chatRepository: ChatRepository,
    val groupDmRepository: GroupDmRepository,
) : ViewModel() {
    var uiState: GroupDmConversationUIState by mutableStateOf(GroupDmConversationUIState.Loading)
        private set

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val room = chatRepository.getChatRoomByIdentifier(chatRoomId)
            if (room == null) {
                uiState = GroupDmConversationUIState.Error("Couldn't find the chat room")
                return@launch
            }
            val isAdmin = groupDmRepository.isCurrentAdmin(chatRoomId, activeUserPublicKey)
            val participants = conversationId?.let { groupDmRepository.participantsOf(chatRoomId, it) }?.takeIf { it.isNotEmpty() } ?: recipients
            val names = participants.map { groupDmRepository.nameOf(it) }
            // A new conversation is the one its recipients make: the same id the first message files under.
            val id = conversationId ?: com.machankura.dedun.compose.nostr.groupdm.GroupDmConversation.idOf(recipients)
            val lines = if (isAdmin) groupDmRepository.observeConversation(chatRoomId, id) else flowOf(emptyList())
            lines.collect { current ->
                if (isAdmin) groupDmRepository.markRead(chatRoomId, id)
                uiState = GroupDmConversationUIState.Loaded(room, participants, names, current, isAdmin)
            }
        }
    }

    /** Where the composer is: idle, preparing a recipient's key, or sending. */
    var composing: Composing by mutableStateOf(Composing.Idle)
        private set

    enum class Composing { Idle, Preparing, Sending }

    /**
     * Asks the group to send [text] (D10). A recipient whose key is still being computed has
     * the composer wait and try again, for a few minutes, while the admins' apps answer.
     */
    fun send(text: String, replyTo: HexKey?, onProposed: (sessionId: String) -> Unit, onNoEvidence: () -> Unit, onFailure: () -> Unit) {
        val loaded = uiState as? GroupDmConversationUIState.Loaded ?: return
        if (composing != Composing.Idle || text.isBlank()) return
        composing = Composing.Sending
        viewModelScope.launch(Dispatchers.IO) {
            var outcome = runCatching { groupDmRepository.send(loaded.localChatRoom, loaded.participants, text, replyTo) }.getOrNull()
            var tries = 0
            while (outcome is GroupDmSender.Outcome.Preparing && tries < PREPARING_TRIES) {
                composing = Composing.Preparing
                delay(PREPARING_INTERVAL)
                outcome = runCatching { groupDmRepository.send(loaded.localChatRoom, loaded.participants, text, replyTo) }.getOrNull()
                tries += 1
            }
            withContext(Dispatchers.Main) {
                composing = Composing.Idle
                when (outcome) {
                    is GroupDmSender.Outcome.Proposed -> onProposed(outcome.sessionId)
                    is GroupDmSender.Outcome.NoEvidence -> onNoEvidence()
                    else -> onFailure()
                }
            }
        }
    }

    fun sendAgain(signingSessionId: String, onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { groupDmRepository.sendAgain(signingSessionId) }
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    companion object {
        private const val PREPARING_TRIES = 60
        private val PREPARING_INTERVAL = kotlin.time.Duration.parse("3s")

        fun factory(
            chatRoomId: String,
            activeUserPublicKey: HexKey,
            conversationId: HexKey?,
            recipients: List<HexKey>,
            chatRepository: ChatRepository,
            groupDmRepository: GroupDmRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { GroupDmConversationViewModel(chatRoomId, activeUserPublicKey, conversationId, recipients, chatRepository, groupDmRepository) }
        }
    }
}
