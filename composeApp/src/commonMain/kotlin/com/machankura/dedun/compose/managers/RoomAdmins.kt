package com.machankura.dedun.compose.managers

import com.vitorpamplona.marmot.groups.currentAdminIdentities
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.database.DedunDatabase

/**
 * Who a keyed room's live admin policy names, as far as the share work reads it
 * (docs/admin-change-rotation.md): the admins, and whether the group's signature
 * governs the room, so that the policy changes only by a signed commit. Every
 * founding member is an admin when the room is made (docs/robust-group-admins.md),
 * so a holder a governed room's policy does not name is one the group took out.
 */
data class RoomAdmins(
    val admins: Set<HexKey>,
    val governed: Boolean,
) {
    /**
     * Whether [member] is someone the group's signature took out of the admins:
     * the room is governed and its policy does not name them.
     */
    fun removedBySignature(member: HexKey): Boolean = governed && member !in admins

    companion object {
        /**
         * Stands in for the live group in tests whose rooms have no MLS state --
         * the share rituals between in-memory devices -- as
         * `ExperimentalFeatures.shareRefreshOverride` stands in for the build.
         */
        var override: (suspend (chatRoomId: String) -> RoomAdmins?)? = null

        /** [chatRoomId]'s admins off its live group, or null when this device holds no group for it. */
        suspend fun of(database: DedunDatabase, chatRoomId: String): RoomAdmins? {
            override?.let { return it(chatRoomId) }
            val room = database.chatRoomDao().findChatRoomById(chatRoomId) ?: return null
            val group = runCatching { database.marmotEngine.groupFor(room.routingId) }.getOrNull() ?: return null
            return RoomAdmins(group.currentAdminIdentities().toSet(), GovernanceGate.isGoverned(group))
        }
    }
}
