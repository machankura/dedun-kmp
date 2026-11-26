package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/** The untyped way into a group's signed statements: an unsigned event, pasted. */
@Serializable
data class ProposeGroupEventRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String, // TODO: have this as a publicKey
    val relayHint: String?
): Route()
