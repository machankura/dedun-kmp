package com.machankura.dedun.compose.managers

import com.vitorpamplona.marmot.groups.currentAdminIdentities
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.database.DedunDatabase

/**
 * Who a keyed room's live admin policy names, as far as the share work reads it
 * (docs/admin-change-rotation.md): the admins, whether the group's signature
 * governs the room -- so that the policy changes only by a signed commit -- and
 * any founding member [FoundingAdmins.check] finds the policy missing, whose
 * absence the group never signed.
 */
data class RoomAdmins(
    val admins: Set<HexKey>,
    val governed: Boolean,
    val missingFounders: Set<HexKey> = emptySet(),
) {
    /**
     * Whether [member] is someone the group's signature took out of the admins:
     * the room is governed, the policy does not name them, and they are not a
     * founder it has been missing from the start.
     */
    fun removedBySignature(member: HexKey): Boolean = governed && member !in admins && member !in missingFounders

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
            val missing = (FoundingAdmins.check(database, chatRoomId) as? FoundingAdmins.Check.Missing)?.publicKeys.orEmpty()
            return RoomAdmins(group.currentAdminIdentities().toSet(), GovernanceGate.isGoverned(group), missing)
        }
    }
}
