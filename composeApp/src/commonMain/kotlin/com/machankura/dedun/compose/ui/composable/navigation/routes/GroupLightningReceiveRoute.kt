package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/** Asking the group's Lightning node for an invoice, from the Lightning wallet screen. */
@Serializable
data class GroupLightningReceiveRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    /** A contact the invoice is for, rather than the active profile: `docs/non-member-deposits.md`, D10. */
    val contactId: String? = null,
) : Route()
