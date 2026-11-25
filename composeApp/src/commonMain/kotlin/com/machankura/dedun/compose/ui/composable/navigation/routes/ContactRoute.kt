package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * One person outside the group [chatRoomId] that it has agreed to count, [contactId], on [chain] --
 * the app's network when null. `docs/non-member-deposits.md`, Phase 4.
 */
@Serializable
data class ContactRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    val contactId: String,
    val chain: String? = null,
): Route()
