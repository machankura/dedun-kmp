package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.MarmotKeyPackage
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom

sealed interface AddMemberToChatRoomConfirmationUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        val profile: Profile,
        val marmotKeyPackage: MarmotKeyPackage
    ): AddMemberToChatRoomConfirmationUIState

    data class Error(
        val message: String
    ): AddMemberToChatRoomConfirmationUIState
    data object Loading: AddMemberToChatRoomConfirmationUIState
}

