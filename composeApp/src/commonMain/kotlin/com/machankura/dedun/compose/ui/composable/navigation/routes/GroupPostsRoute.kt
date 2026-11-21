package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * What the group has said publicly, as itself, newest first, with the way into
 * the composer for a member holding a share of the key.
 * docs/group-nostr-profile-and-posts.md.
 */
@Serializable
data class GroupPostsRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String, // TODO: have this as a publicKey
    val relayHint: String?
): Route()
