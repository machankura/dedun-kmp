package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * How long what the user writes lasts: their default when [chatRoomId] is null, else that
 * conversation's override. docs/disappearing-messages.md, Phase 6.
 */
@Serializable
data class DisappearingMessagesRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String? = null,
): Route()
