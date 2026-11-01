package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * The group wallet's receive addresses: every one paid, the one being handed
 * out, and the next few -- pushed from the wallet screen's receive card.
 */
@Serializable
data class GroupWalletAddressesRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    /** The wallet's network, as `GroupWalletStatementEvent.chainName` spells it; null for the node's own. */
    val chain: String? = null,
): Route()
