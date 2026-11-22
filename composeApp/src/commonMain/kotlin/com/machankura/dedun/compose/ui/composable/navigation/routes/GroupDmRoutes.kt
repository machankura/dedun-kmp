package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/** A group's conversations. docs/group-threshold-ecdh.md, Phase 8. */
@Serializable
data class GroupDmListRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
): Route()

/** One of a group's conversations, or a new one when [conversationId] is null and [recipients] are given (Phase 9). */
@Serializable
data class GroupDmConversationRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    val conversationId: String?,
    val recipients: List<String> = emptyList(),
): Route()
