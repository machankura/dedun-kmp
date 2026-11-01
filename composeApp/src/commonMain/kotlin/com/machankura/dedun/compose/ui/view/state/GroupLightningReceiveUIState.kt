package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.GroupLightningWalletManager

/** Asking the operator's node for an invoice: the form, the wait for the operator's device, the invoice. */
sealed class GroupLightningReceiveUIState {
    data object Loading : GroupLightningReceiveUIState()

    data class Error(val message: String) : GroupLightningReceiveUIState()

    /** No wallet, or no operator to run its node. */
    data object Unavailable : GroupLightningReceiveUIState()

    /** [offer] is the group node's reusable Bolt12 offer, once a snapshot carried one this device checked. */
    data class Form(val localChatRoom: LocalChatRoom, val wallet: GroupLightningWalletManager.Wallet, val offer: String? = null) : GroupLightningReceiveUIState()

    data class Waiting(val requestId: String) : GroupLightningReceiveUIState()

    /** An invoice the operator made, already checked to pay the group's node. */
    data class Invoice(val bolt11: String) : GroupLightningReceiveUIState()
}
