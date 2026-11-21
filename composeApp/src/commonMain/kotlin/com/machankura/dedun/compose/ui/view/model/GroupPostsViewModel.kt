package com.machankura.dedun.compose.ui.view.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.ui.view.state.GroupPostsUIState

/**
 * The group's posts, read once for the screen that shows them, with the
 * profile that bylines them. One read rather than a watch, for the reason
 * `GroupNostrProfileViewModel` gives.
 */
class GroupPostsViewModel(
    val chatRoomId: String,
    initialGroupPostsUIState: GroupPostsUIState,
    val chatRepository: ChatRepository,
): ViewModel() {

    var groupPostsUIState: GroupPostsUIState by mutableStateOf(initialGroupPostsUIState)
        private set

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)

            groupPostsUIState = if (localChatRoom == null) {
                GroupPostsUIState.Error("Couldn't find the group")
            } else {
                GroupPostsUIState.Loaded(
                    localChatRoom = localChatRoom,
                    groupPosts = chatRepository.groupPosts(chatRoomId),
                    groupNostrProfile = chatRepository.groupNostrProfile(chatRoomId),
                    canSign = GroupStatementCapability.roomIsOpen(localChatRoom) &&
                        chatRepository.canSign(chatRoomId),
                )
            }
        }
    }

    companion object {
        fun factory(
            chatRoomId: String,
            initialGroupPostsUIState: GroupPostsUIState = GroupPostsUIState.Loading,
            chatRepository: ChatRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GroupPostsViewModel(
                    chatRoomId = chatRoomId,
                    initialGroupPostsUIState = initialGroupPostsUIState,
                    chatRepository = chatRepository,
                )
            }
        }
    }
}
