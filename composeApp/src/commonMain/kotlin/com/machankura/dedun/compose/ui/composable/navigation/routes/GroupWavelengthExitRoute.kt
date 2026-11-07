package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/** Leaving a group's Wavelength wallet without the Ark operator's help, from the device that runs it. */
@Serializable
data class GroupWavelengthExitRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    val chain: String,
) : Route()
