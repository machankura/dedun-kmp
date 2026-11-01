package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * The form a member proposes a spend from the group's wallet with -- or, with
 * [guardedChatRoomId], from that descendant's guarded wallet, through the
 * group's guardian leaf.
 *
 * [fundChatRoomId] is where the coins go instead of where they come from: the
 * descendant whose guarded wallet a send from the group's own wallet pays. A
 * route naming both is not one the form opens -- `docs/guarded-wallet-funding.md`, D6.
 */
@Serializable
data class GroupSendRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    val relayHint: String?,
    val guardedChatRoomId: String? = null,
    val fundChatRoomId: String? = null,
    /** The wallet's network, as `GroupWalletStatementEvent.chainName` spells it; null for the node's own. */
    val chain: String? = null,
    /** An address to start the form with: a member's payout address (`docs/group-payout-destination.md`, Phase 7). */
    val address: String? = null,
): Route()
