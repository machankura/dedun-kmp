package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.intermdiate.LocalChatMessage

sealed interface ChatMessageListUIState {
    data class Loaded(
        val chatMessageList: List<LocalChatMessage>
    ): ChatMessageListUIState

    data object Error: ChatMessageListUIState
    data object Loading: ChatMessageListUIState
}

