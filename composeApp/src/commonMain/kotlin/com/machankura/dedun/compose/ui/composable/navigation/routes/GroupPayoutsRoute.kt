package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * Every member of the group [chatRoomId] with their payout destination and what they own on
 * [chain] -- the app's network when null -- and paying them out. `docs/payouts-only.md`, Phase 4.
 */
@Serializable
data class GroupPayoutsRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    val chain: String? = null,
): Route()
