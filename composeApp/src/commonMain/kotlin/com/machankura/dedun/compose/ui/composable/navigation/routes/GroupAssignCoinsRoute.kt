package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * The coins of the group [chatRoomId]'s wallet on [chain] -- the app's network when null -- that
 * nobody owns yet, and asking the group to say whose they are. `docs/payouts-only.md`, Phase 3.
 */
@Serializable
data class GroupAssignCoinsRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    val chain: String? = null,
): Route()
