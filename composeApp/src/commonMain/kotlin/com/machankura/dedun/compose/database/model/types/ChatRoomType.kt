package com.machankura.dedun.compose.database.model.types

import kotlinx.serialization.Serializable

/**
 * How a chat room is governed.
 *
 * The choice decides the room's founding admins, written into its epoch-0 admin
 * list. That list is the group's state from then on, and a promotion or demotion
 * changes it; `MarmotInboundManager.reconcileAdmins` and
 * `processGroupMembershipChanges` keep each participant's `adminAt` in line with
 * whatever it says now. See docs/member-admin-promotion.md.
 */
@Serializable
enum class ChatRoomType {
    /** Only the member who created the room administers it. */
    CONVENIENT,

    /**
     * Every member administers the room, and a change needs a threshold of them
     * to approve it. The rules for that threshold are [com.machankura.dedun.compose.managers.Quorum]'s.
     */
    ROBUST,
}
