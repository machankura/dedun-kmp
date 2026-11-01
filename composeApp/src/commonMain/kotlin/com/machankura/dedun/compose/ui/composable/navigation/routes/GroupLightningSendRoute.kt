package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/** Proposing a payment from the group's Lightning wallet, from the Lightning wallet screen. */
@Serializable
data class GroupLightningSendRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
) : Route()
