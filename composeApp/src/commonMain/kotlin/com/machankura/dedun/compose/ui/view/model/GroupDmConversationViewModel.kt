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
            val lines = if (isAdmin && conversationId != null) groupDmRepository.observeConversation(chatRoomId, conversationId) else flowOf(emptyList())
            lines.collect { current ->
                if (isAdmin && conversationId != null) groupDmRepository.markRead(chatRoomId, conversationId)
                uiState = GroupDmConversationUIState.Loaded(room, participants, names, current, isAdmin)
            }
        }
    }

    companion object {
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
