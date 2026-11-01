package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/** The group's Lightning wallet, from its row on the group's screen. */
@Serializable
data class GroupLightningWalletRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
) : Route()
