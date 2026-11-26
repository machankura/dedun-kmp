package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * What the group says about itself on nostr, as the group signed it.
 *
 * The group's screen names the profile in a row; this is where it is read, with,
 * for a member holding a share of the key, the way into the editor. One of the two
 * screens the group's public voice is split across -- see `GroupPostsRoute` -- each
 * pushed from its row on the group's screen. docs/group-nostr-profile-and-posts.md.
 */
@Serializable
data class GroupNostrProfileRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String, // TODO: have this as a publicKey
    val relayHint: String?
): Route()
