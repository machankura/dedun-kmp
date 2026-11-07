package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/** What a device knows of a group's Wavelength wallet in detail: the one place the word VTXO appears. */
@Serializable
data class GroupWavelengthAdvancedRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    val chain: String,
) : Route()
