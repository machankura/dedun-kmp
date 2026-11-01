package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.wallet.GroupAddress

sealed interface GroupWalletAddressesUIState {
    /**
     * The wallet's addresses as the chain last said.
     *
     * [receive] is null until Electrum has said which are used: before that every
     * address reads as unused, and a list that called `/0/0` current would be
     * wrong about a group paid there before.
     */
    data class Loaded(
        val receive: List<GroupAddress>?,
        /** The change addresses that have been used, newest first: empty for a wallet that has never sent. */
        val change: List<GroupAddress> = emptyList(),
    ): GroupWalletAddressesUIState

    data class Error(
        val message: String
    ): GroupWalletAddressesUIState

    data object Loading: GroupWalletAddressesUIState
}
