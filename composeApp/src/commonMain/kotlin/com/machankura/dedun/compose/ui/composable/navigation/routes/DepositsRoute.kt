package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * Every deposit into the group [chatRoomId] on [chain] -- the app's network when null -- and whose
 * each is. `docs/member-deposits.md`, Phase 8.
 */
@Serializable
data class DepositsRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    val chain: String? = null,
): Route()
