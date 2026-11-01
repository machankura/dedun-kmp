package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * One shared-key ceremony, shown and never started.
 *
 * Deliberately says nothing about *what the ceremony is for*. A subgroup's
 * ceremony grows the ladder by a rung -- the parent's birth certificate -- and
 * that is read off `DkgSession.parentChatRoomId`, never off the route. Only the
 * member who opened a subgroup could ever have put it here, and every other way
 * into this screen is from a room; a route that knows more than the room does is
 * a route that is right for one caller and silently wrong for the rest.
 */
@Serializable
data class DkgRitualRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    /**
     * Which of the room's ceremonies to show. Required: this screen shows a
     * ceremony and never offers to start one, so a caller without a ceremony to
     * name is asking to start one and goes to `CreateSharedKeyRoute` instead.
     *
     * A room can hold more than one -- a parent's room hosts every subgroup
     * ceremony the group runs -- so "the room's ceremony" does not name one
     * thing, and falling back to the newest is how a second, whole-room ceremony
     * once ended up on the screen in place of a subgroup's. See
     * docs/ceremony-creation.md.
     */
    val dkgSessionId: String
): Route()
