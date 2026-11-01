package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom

sealed interface ChatRoomMessagingUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom
    ): ChatRoomMessagingUIState

    data class Error(
        val message: String
    ): ChatRoomMessagingUIState
    data object Loading: ChatRoomMessagingUIState
    data object InitiatingNewChat: ChatRoomMessagingUIState
}

