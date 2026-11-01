package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.Profile

sealed interface SelectChatRoomMembersUIState {
    data class Loaded(
        val profiles: List<Profile>
    ): SelectChatRoomMembersUIState

    data class Error(
        val message: String
    ): SelectChatRoomMembersUIState

    data object Loading: SelectChatRoomMembersUIState
}
