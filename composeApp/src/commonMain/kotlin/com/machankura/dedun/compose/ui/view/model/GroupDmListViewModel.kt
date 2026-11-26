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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupDmRepository
import com.machankura.dedun.compose.ui.view.state.GroupDmListUIState

/** A group's conversations, watched (docs/group-threshold-ecdh.md, Phase 8). */
class GroupDmListViewModel(
    val chatRoomId: String,
    val activeUserPublicKey: HexKey,
    val chatRepository: ChatRepository,
    val groupDmRepository: GroupDmRepository,
) : ViewModel() {
    var uiState: GroupDmListUIState by mutableStateOf(GroupDmListUIState.Loading)
        private set

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val room = chatRepository.getChatRoomByIdentifier(chatRoomId)
            if (room == null) {
                uiState = GroupDmListUIState.Error("Couldn't find the chat room")
                return@launch
            }
            val isAdmin = groupDmRepository.isCurrentAdmin(chatRoomId, activeUserPublicKey)
            combine(groupDmRepository.observeConversations(chatRoomId), groupDmRepository.observePending(chatRoomId)) { conversations, pending ->
                // D12: only a current admin sees anything of the group's mail.
                GroupDmListUIState.Loaded(
                    localChatRoom = room,
                    conversations = if (isAdmin) conversations else emptyList(),
                    pending = if (isAdmin) pending else com.machankura.dedun.compose.ui.view.state.GroupDmPending(),
                    isAdmin = isAdmin,
                )
            }.collect { uiState = it }
        }
    }

    companion object {
        fun factory(chatRoomId: String, activeUserPublicKey: HexKey, chatRepository: ChatRepository, groupDmRepository: GroupDmRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { GroupDmListViewModel(chatRoomId, activeUserPublicKey, chatRepository, groupDmRepository) } }
    }
}
