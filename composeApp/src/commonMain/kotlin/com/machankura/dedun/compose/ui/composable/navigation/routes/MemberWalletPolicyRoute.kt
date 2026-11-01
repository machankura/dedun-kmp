package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * Whether the group [chatRoomId] guards its members' wallets, at what delay,
 * and closing it again -- `docs/member-wallet-delay.md`, Phase 6.
 */
@Serializable
data class MemberWalletPolicyRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
): Route()
