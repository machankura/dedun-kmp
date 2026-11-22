package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * Starting a group's key ceremony in a NIP-17 room, and nothing else.
 *
 * The one place a room's members ask for a key outside group creation. Group
 * creation and the subgroup picker start their own ceremonies because they already
 * know what each is for; this is for a room whose members want one -- their first
 * group, or another beside the ones they have (docs/nip17-multiple-ceremonies.md).
 * The ceremony screen that follows only ever shows a ceremony -- it used to offer
 * to start one too, from the same button the approvals used, which is how a member
 * joining a subgroup's ceremony opened a second. See docs/ceremony-creation.md.
 */
@Serializable
data class CreateSharedKeyRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    /**
     * The failed ceremony this is a second try at, whose name and quorum the screen
     * starts from -- or null for a new group. Without it a retry of the room's
     * second group would come back under the room's first group's name.
     */
    val retrying: String? = null,
): Route()
