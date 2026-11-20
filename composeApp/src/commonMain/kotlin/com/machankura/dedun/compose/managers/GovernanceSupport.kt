package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.vitorpamplona.marmot.groups.currentAdminIdentities
import com.vitorpamplona.marmot.groups.currentGroupState
import com.vitorpamplona.mls.group.MlsGroup
import com.vitorpamplona.mls.tree.Credential
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import kotlinx.coroutines.CancellationException
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import kotlin.time.Clock
import kotlin.time.Instant
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.nostr.MarmotProfile
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.frost.GovernanceSupportEvent

/**
 * Who in a keyed room has said their device follows signed commits, and this
 * device saying it. docs/member-admin-promotion.md, D4 and Phase 4b.
 *
 * The rule cannot be switched on while anybody in the room would be left behind
 * by it: a device that does not know it applies the bare commits Dedun drops and
 * reads none of the envelopes, and is in a different group from the first change.
 * Once everybody has, one device switches the rule on by itself ([switchWhereOwed]);
 * nobody opens a screen or presses anything.
 *
 * So each device says, once per room, that it can follow: when it is welcomed in,
 * when it starts, when the room's screen is opened, and back when it hears another
 * say it.
 *
 * Joining has to be one of them. A member invited after the others said it cannot
 * read what they said -- it was sent at an epoch before theirs -- so it never hears
 * anybody to answer, and nobody says it again. Until it opened the room's screen
 * the room waited on it, and nobody could be made an admin. See
 * docs/remove-convenient-groups.md, "After the build".
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

    /**
     * [announce] for every room this device is in, for the members who joined
     * before joining was one of the times it is said. Once per start, and a read
     * with no write for a room that has it already. One room that will not open is
     * logged and passed over.
     *
     * @return how many rooms it was said in now.
     */
    suspend fun announceAll(database: DedunDatabase): Int =
        database.chatRoomDao().getChatRoomIdsWithMlsState().count { chatRoomId ->
            runCatching {
                val room = database.chatRoomDao().findChatRoomById(chatRoomId) ?: return@runCatching false
                if (room.chatRoom.leftGroupAt != null || room.chatRoom.marmotProfile() != MarmotProfile.CURRENT) return@runCatching false
                val group = database.marmotEngine.groupFor(room.routingId) ?: return@runCatching false
                announce(database, chatRoomId, group, room.chatRoom.userPublicKey)
            }.getOrElse {
                logger.e("Could not say $chatRoomId follows signed commits", it)
                false
            }
        }

    /**
     * The one device that switches [group]'s rule on: the admin in its lowest leaf --
     * in a room as it was made, its creator. Null when no admin holds a leaf.
     *
     * One, because the library settles two commits made at the same epoch for every
     * device that only watched them, but not for the two that made them: those swap
     * branches and stay apart (docs/marmot-v2.md, Phase 8). Every admin switching the
     * moment the last member said it would be exactly that race. The lowest leaf is
     * read off the group's state, so every device names the same one without a word
     * between them.
     */
    fun switcherOf(group: MlsGroup): HexKey? {
        val admins = group.currentAdminIdentities()
        return group.members()
            .sortedBy { (leafIndex, _) -> leafIndex }
            .firstNotNullOfOrNull { (_, leaf) ->
                (leaf.credential as? Credential.Basic)?.identity?.toHexKey()?.takeIf { it in admins }
            }
    }

    /**
     * Whether this device owes [room] the switch now: a keyed room on the current
     * profile, open, not yet requiring the group's signature, whose [switcherOf] is
     * this device's account, where every member has said they follow signed commits
     * and enough of them are admins. The checks `requireSignatures` makes, read first
     * so a room that is not ready is passed over rather than refused.
     */
    suspend fun switchOwed(database: DedunDatabase, room: LocalChatRoom, group: MlsGroup): Boolean {
        val me = room.chatRoom.userPublicKey
        if (room.chatRoom.leftGroupAt != null || room.chatRoom.marmotProfile() != MarmotProfile.CURRENT) return false
        if (!AdminGovernance.isKeyedRoom(group) || GovernanceGate.isGoverned(group)) return false
        if (group.currentGroupState().isDisbanded) return false
        if (switcherOf(group) != me) return false
        if (missing(database, room.chatRoom.id, group).isNotEmpty()) return false
        val admins = group.currentAdminIdentities() intersect AdminGovernance.members(group)
        return admins.size >= AdminGovernance.adminFloor(database, room.chatRoom.id, group)
    }

    /**
     * Switches the rule on in every room of [me]'s that is owed it -- [switchOwed] --
     * saying first, where it has not, that this device follows signed commits, since
     * it is a member too. Run by `GovernanceFollowThrough` whenever a member says it,
     * and when it starts, so the switch needs nobody to open a screen or press
     * anything. A switch still waiting for a relay is not sent twice; one that fails
     * is logged and tried again on the next pass.
     *
     * @return how many rooms a switch was sent in.
     */
    suspend fun switchWhereOwed(database: DedunDatabase, me: HexKey): Int =
        database.chatRoomDao().getChatRoomIdsWithMlsState().count { chatRoomId ->
            runCatching {
                val room = database.chatRoomDao().findChatRoomById(chatRoomId) ?: return@runCatching false
                if (room.chatRoom.userPublicKey != me) return@runCatching false
                val engine = database.marmotEngine
                val group = engine.groupFor(room.routingId) ?: return@runCatching false
                if (switcherOf(group) != me) return@runCatching false
                announce(database, chatRoomId, group, me)
                if (!switchOwed(database, room, group)) return@runCatching false
                if (!engine.gate.canPrepareLocalCommit(room.routingId)) return@runCatching false
                database.marmotOutboundDao().requireSignatures(room)
                logger.i("Switched on the group's signature for changes in $chatRoomId")
                true
            }.getOrElse {
                if (it is CancellationException) throw it
                logger.w("Couldn't switch on the group's signature in $chatRoomId: ${it.message}")
                false
            }
        }

    private val logger = Logger.withTag("GovernanceSupport")
}
