package com.machankura.dedun.compose.managers

import com.vitorpamplona.mls.group.MlsGroup
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import kotlin.time.Clock
import kotlin.time.Instant
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.nostr.frost.GovernanceSupportEvent

/**
 * Who in a keyed room has said their device follows signed commits, and this
 * device saying it. docs/member-admin-promotion.md, D4 and Phase 4b.
 *
 * The rule cannot be switched on while anybody in the room would be left behind
 * by it: a device that does not know it applies the bare commits Dedun drops and
 * reads none of the envelopes, and is in a different group from the first change.
 * So each device says, once per room, that it can follow -- and says it back when
 * it hears another say it, which is how a member who never opens the room's screen
 * still gets counted.
 */
object GovernanceSupport {

    /** The accounts that have said, in [chatRoomId], that they follow signed commits. */
    suspend fun supporters(database: DedunDatabase, chatRoomId: String): Set<HexKey> =
        database.marmotInnerEventDao().getByChatRoomAndKinds(chatRoomId, listOf(GovernanceSupportEvent.KIND))
            .filter { row -> row.tags.any { it.size >= 2 && it[0] == GovernanceSupportEvent.VERSION_TAG && it[1] == GovernanceSupportEvent.VERSION } }
            .map { it.publicKey }
            .toSet()

    /** The members of [group] who have not said it yet. */
    suspend fun missing(database: DedunDatabase, chatRoomId: String, group: MlsGroup): Set<HexKey> =
        AdminGovernance.members(group) - supporters(database, chatRoomId)

    /**
     * Says, for [me], that this device follows signed commits in [chatRoomId] -- once:
     * nothing happens when it already has. Only in a keyed room, which is the only
     * kind the rule can be switched on in. Returns whether it said it now.
     */
    suspend fun announce(database: DedunDatabase, chatRoomId: String, group: MlsGroup, me: HexKey): Boolean {
        if (!AdminGovernance.isKeyedRoom(group)) return false
        if (me in supporters(database, chatRoomId)) return false
        val tags = GovernanceSupportEvent.assembleTags(chatRoomId)
        val createdAt = Clock.System.now().epochSeconds
        val id = EventHasher.hashId(pubKey = me, createdAt = createdAt, kind = GovernanceSupportEvent.KIND, tags = tags, content = "")
        // Unsent: the outbound worker puts an inner event with no group event behind
        // it into the room, as it does for every manager's messages.
        database.marmotInnerEventDao().upsert(
            MarmotInnerEvent(
                id = id,
                publicKey = me,
                kind = GovernanceSupportEvent.KIND,
                createdAt = Instant.fromEpochSeconds(createdAt),
                tags = tags,
                content = "",
                chatRoomId = chatRoomId,
            )
        )
        return true
    }
}
