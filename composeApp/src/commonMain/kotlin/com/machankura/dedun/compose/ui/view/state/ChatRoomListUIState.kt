package com.machankura.dedun.compose.ui.view.state

sealed interface ChatRoomListUIState {
    data class Loaded(
        val chatRoomList: List<com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom>,
        /** Rooms with unread lines, by room id. A room absent here has none. */
        val unread: Map<String, com.machankura.dedun.compose.database.dao.RoomUnread> = emptyMap(),
    ): ChatRoomListUIState

    data object Error: ChatRoomListUIState
    data object Loading: ChatRoomListUIState
}
