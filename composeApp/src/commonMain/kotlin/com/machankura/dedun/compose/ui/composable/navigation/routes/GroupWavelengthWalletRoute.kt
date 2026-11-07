package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/** The group's Wavelength wallet on one network, as `GroupWalletStatementEvent.chainName` spells it, from its row on the group's screen. */
@Serializable
data class GroupWavelengthWalletRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    val chain: String,
) : Route()
