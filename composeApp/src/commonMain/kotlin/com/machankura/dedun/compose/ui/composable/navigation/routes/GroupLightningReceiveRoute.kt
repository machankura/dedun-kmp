package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/** Asking the group's Lightning node for an invoice, from the Lightning wallet screen. */
@Serializable
data class GroupLightningReceiveRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
) : Route()
