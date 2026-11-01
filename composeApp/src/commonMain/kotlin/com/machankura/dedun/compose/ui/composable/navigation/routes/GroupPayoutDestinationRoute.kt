package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * Where the group [chatRoomId] pays the active profile on [chain] -- the app's network when
 * null. `docs/group-payout-destination.md`, Phase 4.
 */
@Serializable
data class GroupPayoutDestinationRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    val chain: String? = null,
): Route()
