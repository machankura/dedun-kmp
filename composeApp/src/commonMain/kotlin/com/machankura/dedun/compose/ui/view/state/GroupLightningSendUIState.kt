package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.GroupLightningWalletManager

/** Proposing a payment from the group's Lightning wallet. */
sealed class GroupLightningSendUIState {
    data object Loading : GroupLightningSendUIState()

    data class Error(val message: String) : GroupLightningSendUIState()

    /** No wallet, or no operator whose node would pay. */
    data object Unavailable : GroupLightningSendUIState()

    data class Form(val localChatRoom: LocalChatRoom, val wallet: GroupLightningWalletManager.Wallet) : GroupLightningSendUIState()
}
