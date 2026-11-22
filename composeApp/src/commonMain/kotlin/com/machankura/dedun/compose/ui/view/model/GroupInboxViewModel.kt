package com.machankura.dedun.compose.ui.view.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupInboxRepository
import com.machankura.dedun.compose.ui.view.state.GroupInboxConversationUIState
import com.machankura.dedun.compose.ui.view.state.GroupInboxSettingsUIState
import com.machankura.dedun.compose.ui.view.state.GroupInboxUIState

/**
 * The group's inbox, watched rather than read once: mail arrives while the screen is open, and
 * the point of the screen is to see it (docs/group-inbox.md, Phase 7).
 */
class GroupInboxViewModel(
    val chatRoomId: String,
    initialUIState: GroupInboxUIState,
    val chatRepository: ChatRepository,
    val inboxRepository: GroupInboxRepository,
): ViewModel() {
    var uiState: GroupInboxUIState by mutableStateOf(initialUIState)
        private set

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val room = chatRepository.getChatRoomByIdentifier(chatRoomId) ?: run {
                uiState = GroupInboxUIState.Error("Couldn't find the group")
                return@launch
            }
            combine(
                inboxRepository.observeConversations(chatRoomId),
                inboxRepository.observeUnopenedCount(chatRoomId),
                inboxRepository.observeStatus(room, room.chatRoom.userPublicKey),
            ) { conversations, unopenable, status ->
                GroupInboxUIState.Loaded(room, conversations, unopenable, status)
            }.collect { uiState = it }
        }
    }

    companion object {
        fun factory(
            chatRoomId: String,
            initialUIState: GroupInboxUIState = GroupInboxUIState.Loading,
            chatRepository: ChatRepository,
            inboxRepository: GroupInboxRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { GroupInboxViewModel(chatRoomId, initialUIState, chatRepository, inboxRepository) }
        }
    }
}

/** One conversation in the group's inbox, marked read as it is shown (docs/group-inbox.md, Phase 7). */
class GroupInboxConversationViewModel(
    val chatRoomId: String,
    val conversationId: String,
    initialUIState: GroupInboxConversationUIState,
    val chatRepository: ChatRepository,
    val inboxRepository: GroupInboxRepository,
): ViewModel() {
    var uiState: GroupInboxConversationUIState by mutableStateOf(initialUIState)
        private set

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val room = chatRepository.getChatRoomByIdentifier(chatRoomId) ?: run {
                uiState = GroupInboxConversationUIState.Error("Couldn't find the group")
                return@launch
            }
            inboxRepository.observeConversation(chatRoomId, conversationId).collect { messages ->
                val participants = messages.lastOrNull()?.participantList().orEmpty()
                val names = inboxRepository.namesOf(participants)
                uiState = GroupInboxConversationUIState.Loaded(
                    localChatRoom = room,
                    names = participants.map { names[it] ?: it.take(8) },
                    lines = messages.map { message ->
                        GroupInboxConversationUIState.Line(
                            rumorId = message.rumorId,
                            text = Event.fromJsonOrNull(message.rumorJson)?.content.orEmpty(),
                            createdAt = message.createdAt,
                            outgoing = message.outgoing,
                            senderName = if (message.outgoing) "" else names[message.senderPublicKey] ?: message.senderPublicKey.take(8),
                        )
                    },
                )
                if (messages.any { !it.outgoing && it.readAt == null }) inboxRepository.markRead(chatRoomId, conversationId)
            }
        }
    }

    companion object {
        fun factory(
            chatRoomId: String,
            conversationId: String,
            initialUIState: GroupInboxConversationUIState = GroupInboxConversationUIState.Loading,
            chatRepository: ChatRepository,
            inboxRepository: GroupInboxRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { GroupInboxConversationViewModel(chatRoomId, conversationId, initialUIState, chatRepository, inboxRepository) }
        }
    }
}

/** The group's inbox key: setting one up, its ceremony, rotating it (docs/group-inbox.md, Phase 7). */
class GroupInboxSettingsViewModel(
    val chatRoomId: String,
    initialUIState: GroupInboxSettingsUIState,
    val chatRepository: ChatRepository,
    val inboxRepository: GroupInboxRepository,
): ViewModel() {
    var uiState: GroupInboxSettingsUIState by mutableStateOf(initialUIState)
        private set

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val room = chatRepository.getChatRoomByIdentifier(chatRoomId) ?: run {
                uiState = GroupInboxSettingsUIState.Error("Couldn't find the group")
                return@launch
            }
            inboxRepository.observeStatus(room, room.chatRoom.userPublicKey).collect { status ->
                val busy = (uiState as? GroupInboxSettingsUIState.Loaded)?.busy ?: false
                uiState = GroupInboxSettingsUIState.Loaded(room, status, busy)
            }
        }
    }

    private fun act(onDone: (String?) -> Unit, action: suspend (GroupInboxSettingsUIState.Loaded) -> Unit) {
        val loaded = uiState as? GroupInboxSettingsUIState.Loaded ?: return
        if (loaded.busy) return
        uiState = loaded.copy(busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            val failure = runCatching { action(loaded) }.exceptionOrNull()
            (uiState as? GroupInboxSettingsUIState.Loaded)?.let { uiState = it.copy(busy = false) }
            onDone(failure?.let { it.message ?: it::class.simpleName.orEmpty() })
        }
    }

    /** Starts the first ceremony. [onDone] gets null, or why it could not. */
    fun setUp(relays: List<NormalizedRelayUrl>, onDone: (String?) -> Unit) =
        act(onDone) { inboxRepository.setUp(it.localChatRoom, relays) }

    fun rotate(onDone: (String?) -> Unit) = act(onDone) { inboxRepository.rotate(it.localChatRoom) }

    fun abandon(ceremonyId: String, onDone: (String?) -> Unit) =
        act(onDone) { inboxRepository.abandon(it.localChatRoom, ceremonyId) }

    companion object {
        fun factory(
            chatRoomId: String,
            initialUIState: GroupInboxSettingsUIState = GroupInboxSettingsUIState.Loading,
            chatRepository: ChatRepository,
            inboxRepository: GroupInboxRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { GroupInboxSettingsViewModel(chatRoomId, initialUIState, chatRepository, inboxRepository) }
        }
    }
}
