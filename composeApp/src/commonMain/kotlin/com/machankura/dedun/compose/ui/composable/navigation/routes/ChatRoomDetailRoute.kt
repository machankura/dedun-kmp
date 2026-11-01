package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

@Serializable
data class ChatRoomDetailRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String, // TODO: have this as a publicKey
    val relayHint: String?
): Route()