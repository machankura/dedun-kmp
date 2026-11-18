package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * The active profile transferring part of its balance in the group [chatRoomId]'s wallet on [rail] -- `wavelength`,
 * `lightning` or `onchain` -- on [chain], the app's network when null, to another member. `docs/member-transfers.md`, Phase 4.
 */
@Serializable
data class GroupTransferRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    val rail: String,
    val chain: String? = null,
) : Route()
