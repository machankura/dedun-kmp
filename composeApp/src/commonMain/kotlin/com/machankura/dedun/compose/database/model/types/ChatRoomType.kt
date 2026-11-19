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
     * to approve it.
     */
    ROBUST;

    companion object {
        /**
         * Smallest group [ROBUST] is offered for. Below three, a majority is not a
         * meaningful check: two admins means every change needs both of them, and
         * one means the creator is deciding alone — which is [CONVENIENT] already.
         */
        const val MINIMUM_ROBUST_GROUP_SIZE = 3

        /**
         * Fewest admins a [ROBUST] room can be made to run on. One approval is not a
         * quorum — it is one person acting alone, which is [CONVENIENT].
         */
        const val MINIMUM_QUORUM = 2

        /**
         * The quorum a [ROBUST] group of [adminCount] starts on — a simple majority,
         * so no half of the group can move without the other. The user can move it
         * anywhere in [quorumRange] from there.
         */
        fun defaultQuorum(adminCount: Int): Int = maxOf(MINIMUM_QUORUM, adminCount / 2 + 1)

        /**
         * Quorums a [ROBUST] group of [adminCount] can be run on: never fewer than
         * [MINIMUM_QUORUM], never more than the admins who exist to approve.
         */
        fun quorumRange(adminCount: Int): IntRange = MINIMUM_QUORUM..adminCount

        /**
         * The fewest admins a [ROBUST] group may ever have: [MINIMUM_ROBUST_GROUP_SIZE],
         * or its quorum [threshold] when that is higher. In a group around a key the
         * admins are its share holders, so fewer than [threshold] of them is a key
         * that can never sign again -- and nothing about the group could change after
         * that. Null [threshold] means this device does not know it, and the size
         * floor alone applies. docs/member-admin-promotion.md, R3 and D8.
         */
        fun minimumAdmins(threshold: Int?): Int = maxOf(MINIMUM_ROBUST_GROUP_SIZE, threshold ?: 0)

        /** Whether a group of [memberCount] people can be run as [ROBUST]. */
        fun isRobustAvailable(memberCount: Int): Boolean = memberCount >= MINIMUM_ROBUST_GROUP_SIZE
    }
}
