package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

@Serializable
data class BroadcastGroupSignedEventRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String, // TODO: have this as a publicKey
    val relayHint: String?,
    /** The `GroupSignedEvent` to send, which is also the event id the group signed. */
    val signedEventId: String,
): Route()
