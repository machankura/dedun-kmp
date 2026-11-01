package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/** Asking the group to buy inbound liquidity for its Lightning wallet, from the Lightning wallet screen. */
@Serializable
data class GroupLightningLiquidityRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
) : Route()
