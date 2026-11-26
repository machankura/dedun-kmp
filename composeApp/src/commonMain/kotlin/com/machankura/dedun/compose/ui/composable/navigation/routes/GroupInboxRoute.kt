package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/** The group's inbox: its conversations, on an admin's device. docs/group-inbox.md, Phase 7. */
@Serializable
data class GroupInboxRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    val relayHint: String?
): Route()

/** One conversation in the group's inbox, and the way to reply to it. docs/group-inbox.md. */
@Serializable
data class GroupInboxConversationRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    val relayHint: String?,
    val conversationId: String,
): Route()

/**
 * The group's inbox key: setting one up, the ceremony that makes it, what the group announced,
 * and rotating it. docs/group-inbox.md.
 */
@Serializable
data class GroupInboxSettingsRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    val relayHint: String?
): Route()
