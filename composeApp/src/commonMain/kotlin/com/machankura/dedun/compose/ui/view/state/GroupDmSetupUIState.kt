package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom

sealed interface GroupDmSetupUIState {
    data object Loading : GroupDmSetupUIState
    data class Error(val message: String) : GroupDmSetupUIState
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        /** What the field starts from: what the group signed, or the app's DM relays. */
        val relays: List<String>,
        /** A current admin holding a share: who may ask. */
        val canPropose: Boolean,
    ) : GroupDmSetupUIState
}
