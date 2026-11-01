package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * The group's on-chain wallet: its balance, the address to be paid at, its
 * coins, and -- for a group that has not opened one -- the way to ask it to.
 */
@Serializable
data class GroupWalletRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    val relayHint: String?,
    /** The wallet's network, as `GroupWalletStatementEvent.chainName` spells it; null for the node's own. */
    val chain: String? = null,
): Route()
