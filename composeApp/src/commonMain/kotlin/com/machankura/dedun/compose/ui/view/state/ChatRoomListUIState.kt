package com.machankura.dedun.compose.ui.view.state

sealed interface ChatRoomListUIState {
    data class Loaded(
        val chatRoomList: List<com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom>
    ): ChatRoomListUIState

    data object Error: ChatRoomListUIState
    data object Loading: ChatRoomListUIState
}

