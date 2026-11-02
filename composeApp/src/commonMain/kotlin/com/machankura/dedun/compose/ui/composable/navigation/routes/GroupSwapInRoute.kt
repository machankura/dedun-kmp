package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * Depositing onchain into the group's Lightning wallet: the member's own swap-in address, their coins on
 * it, and the addresses used before -- from the Lightning receive form (docs/group-lightning-swap-in.md, Phase 6).
 */
@Serializable
data class GroupSwapInRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
) : Route()
