package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.Profile

sealed interface SelectChatRoomTypeUIState {
    data class Loaded(
        /** The picked members, for naming them back to the user. */
        val members: List<Profile>
    ): SelectChatRoomTypeUIState

    data class Error(
        val message: String
    ): SelectChatRoomTypeUIState

    data object Loading: SelectChatRoomTypeUIState
}
