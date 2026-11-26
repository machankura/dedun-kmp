package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/** Where a group chooses the relays it receives messages on. docs/group-threshold-ecdh.md, D8. */
@Serializable
data class GroupDmSetupRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
): Route()
