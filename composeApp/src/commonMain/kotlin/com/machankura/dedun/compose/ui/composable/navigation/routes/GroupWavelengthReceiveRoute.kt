package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/** A member's address for paying the group's Wavelength wallet on [chain]. */
@Serializable
data class GroupWavelengthReceiveRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    val chain: String,
) : Route()
