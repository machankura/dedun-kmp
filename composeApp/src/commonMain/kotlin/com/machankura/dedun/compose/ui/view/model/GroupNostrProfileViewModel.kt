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
import com.machankura.dedun.compose.ui.view.state.GroupNostrProfileUIState

/**
 * The group's nostr profile, read once for the screen that shows it.
 *
 * One read rather than a watch, the way the group's screen reads it: a profile
 * changes when a quorum signs a new one, which lands as a group event this
 * device processes, and the screen is re-entered -- and re-read -- on the way
 * back from the signing session that made it.
 */
class GroupNostrProfileViewModel(
    val chatRoomId: String,
    initialGroupNostrProfileUIState: GroupNostrProfileUIState,
    val chatRepository: ChatRepository,
): ViewModel() {

    var groupNostrProfileUIState: GroupNostrProfileUIState by mutableStateOf(
        initialGroupNostrProfileUIState
    )
        private set

    fun initiate() {
        viewModelScope.launch(Dispatchers.IO) {
            val localChatRoom = chatRepository.getChatRoomByIdentifier(chatRoomId)

            groupNostrProfileUIState = if (localChatRoom == null) {
                GroupNostrProfileUIState.Error("Couldn't find the group")
            } else {
                GroupNostrProfileUIState.Loaded(
                    localChatRoom = localChatRoom,
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
            initialGroupNostrProfileUIState: GroupNostrProfileUIState =
                GroupNostrProfileUIState.Loading,
            chatRepository: ChatRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                GroupNostrProfileViewModel(
                    chatRoomId = chatRoomId,
                    initialGroupNostrProfileUIState = initialGroupNostrProfileUIState,
                    chatRepository = chatRepository,
                )
            }
        }
    }
}
