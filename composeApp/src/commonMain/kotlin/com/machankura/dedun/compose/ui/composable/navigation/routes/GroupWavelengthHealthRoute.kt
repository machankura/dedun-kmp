package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * Whether the group's Wavelength wallet on [chain] is all right, as a checklist.
 *
 * B7 of `docs/group-wavelength-redesign.md`. Its own screen rather than more cards on the wallet
 * screen: the five obligations are read together or not at all, and a member who wants to know whether
 * anything needs doing should not have to interpret five different cards to find out.
 */
@Serializable
data class GroupWavelengthHealthRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    val chain: String,
) : Route()
