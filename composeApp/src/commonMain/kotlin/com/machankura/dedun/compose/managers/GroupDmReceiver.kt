package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.nostr.DisappearingMessages
import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import com.machankura.dedun.compose.ExperimentalFeatures
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.GroupDmMessage
import com.machankura.dedun.compose.database.model.GroupDmWrap
import com.machankura.dedun.compose.database.model.GroupDmWrapState
import com.machankura.dedun.compose.database.model.GroupEcdhRoundState
import com.machankura.dedun.compose.nostr.groupdm.EcdhPurpose
import com.machankura.dedun.compose.nostr.groupdm.Evidence
import com.machankura.dedun.compose.nostr.groupdm.GroupDmConversation
import com.machankura.dedun.compose.nostr.groupdm.GroupDmSeal
import com.machankura.dedun.compose.nostr.groupdm.GroupEcdhPayload

/**
 * A room whose mail this device reads: where to ask for it, and the newest wrap already in
 * its ledger, from which `live-group-dm` asks two days back (NIP-59 back-dates wraps).
 */
data class GroupDmInbox(val roomId: HexKey, val relays: List<String>, val newestWrapAt: Long?)

/**
 * A group's mail, from a wrap on a relay to a message on the admins' screens (D9 of
 * docs/group-threshold-ecdh.md). Its own pipeline: nothing here writes a `GiftWrapMessage`,
 * `GiftWrapPayload` or `NostrEvent` row, because the personal one is built around one
 * identity and would file the group's correspondents among the admin's own.
 *
 * ```
 * wrap → ledger → wrap round → seal → seal round, or the kept key → rumor → GroupDmMessage
 * ```
 *
 * Every admin's device runs every step and combines for itself; nothing is decrypted once and
 * passed round. [step] is driven from [GroupEcdhManager.advance], so a partial that completes
 * a round moves the message on in the same pass.
 */
object GroupDmReceiver {
    private val logger = Logger.withTag("GroupDmReceiver")

    /**
     * New wrap rounds this device opens per room per hour (Phase 7): anyone can make the
     * admins' devices work by sending wraps. Beyond it wraps wait in the ledger, newest first.
     * Two batches' worth at Phase 0's size. A `var` so a test can see the cap without sixty wraps.
     */
    internal var maxNewWrapRoundsPerHour = 60

    /**
     * Whether this device reads [chatRoomId]'s mail: the feature on, its member a current
     * admin holding a share, and the group has signed where it receives messages (D7, D8).
     */
    suspend fun readsFor(database: DedunDatabase, chatRoomId: HexKey, userPublicKey: HexKey): Boolean {
        if (!ExperimentalFeatures.groupDirectMessages) return false
        val room = database.chatRoomDao().findChatRoomById(chatRoomId) ?: return false
        if (room.chatRoom.userPublicKey != userPublicKey) return false
        if (GroupDmRelays.relaysOf(database, chatRoomId) == null) return false
        val admins = RoomAdmins.of(database, chatRoomId)?.admins ?: return false
        if (userPublicKey !in admins) return false
        val key = FrostSigningManager.completedKey(database, chatRoomId) ?: return false
        return ShareGenerations.active(database, key).signerIds.containsKey(userPublicKey)
    }

    /** Whether one of the group inbox's keys this device holds opens [wrap] (docs/group-inbox.md). */
    private suspend fun opensWithInboxKey(database: DedunDatabase, roomId: HexKey, userPublicKey: HexKey, wrap: Event): Boolean {
        if (!ExperimentalFeatures.groupInbox) return false
        val secrets = database.inboxKeyDao().getKeys(roomId, userPublicKey).mapNotNull { it.secretKey?.hexToByteArray() }
        return secrets.isNotEmpty() && com.machankura.dedun.compose.nostr.inbox.InboxWrap.open(wrap, secrets) != null
    }

    /** The room a wrap is addressed to, among those this device reads for. */
    suspend fun roomFor(database: DedunDatabase, wrap: Event, userPublicKey: HexKey): HexKey? {
        if (wrap.kind != GiftWrapEvent.KIND) return null
        return wrap.tags.filter { it.size >= 2 && it[0] == "p" }.map { it[1].lowercase() }.distinct()
            .firstOrNull { readsFor(database, it, userPublicKey) }
    }

    /**
     * A wrap from a relay: into the ledger of the room it is addressed to, if this device reads
     * for one, and moved as far as it can go. Whether it was the group's. Redelivery is a no-op.
     */
    suspend fun receive(database: DedunDatabase, wrap: Event, userPublicKey: HexKey, nostrPrivateKey: ByteArray): Boolean {
        val roomId = roomFor(database, wrap, userPublicKey) ?: return false
        // The group's NIP-4E inbox key opens what a decoupled sender wrote, at once and with no
        // round: it is tried first, and a round is opened only for what it cannot open
        // (docs/group-threshold-ecdh.md, *Against the NIP-4E plan*).
        if (opensWithInboxKey(database, roomId, userPublicKey, wrap)) {
            GroupInboxManager.receive(database, wrap, userPublicKey)
            return true
        }
        // The wrap is its own evidence (D5): signed by its pubkey and addressed to the group.
        if (!Evidence.allows(EcdhPurpose.WRAP, wrap.pubKey, wrap, roomId)) return true
        database.groupDmDao().insertWrap(
            GroupDmWrap(id = wrap.id, chatRoomId = roomId, wrapJson = wrap.toJson(), createdAt = Instant.fromEpochSeconds(wrap.createdAt))
        )
        val room = database.chatRoomDao().findChatRoomById(roomId) ?: return true
        GroupEcdhManager.contextFor(database, room, userPublicKey, nostrPrivateKey)?.let { GroupEcdhManager.advance(database, it) }
        return true
    }

    /**
     * One pass over [context]'s ledger: opens wrap rounds up to the cap, opens seal rounds for
     * seals whose key is not kept, and files every message whose keys are in hand. Returns
     * whether it changed anything, so the sweep knows to go round again.
     */
    suspend fun step(database: DedunDatabase, context: GroupEcdhManager.RoomContext): Boolean {
        val dao = database.groupDmDao()
        var changed = false

        // Wraps whose round this device has not opened, queued ones included, newest first.
        val pending = (dao.getWrapsInState(context.roomId, GroupDmWrapState.WAITING.name) +
            dao.getWrapsInState(context.roomId, GroupDmWrapState.QUEUED.name))
            .filter { it.sealJson == null }
            .sortedByDescending { it.createdAt }
        val since = Clock.System.now() - 1.hours
        var budget = maxNewWrapRoundsPerHour - dao.countWrapRoundsSince(context.roomId, since)
        pending.forEach { ledger ->
            val wrap = runCatching { Event.fromJson(ledger.wrapJson) }.getOrNull() ?: return@forEach
            val roundId = GroupEcdhPayload.roundId(context.roomId, context.generation, wrap.pubKey)
            if (dao.getRound(roundId) != null) {
                if (ledger.state == GroupDmWrapState.QUEUED) { dao.upsertWrap(ledger.copy(state = GroupDmWrapState.WAITING)); changed = true }
                return@forEach
            }
            if (budget > 0) {
                GroupEcdhManager.ensureRound(database, context, wrap.pubKey, EcdhPurpose.WRAP, ref = ledger.id)
                if (ledger.state == GroupDmWrapState.QUEUED) dao.upsertWrap(ledger.copy(state = GroupDmWrapState.WAITING))
                budget -= 1
                changed = true
            } else if (ledger.state != GroupDmWrapState.QUEUED) {
                dao.upsertWrap(ledger.copy(state = GroupDmWrapState.QUEUED))
                changed = true
            }
        }

        // Wraps opened to a seal: the seal's key kept, or a round for it.
        dao.getWrapsInState(context.roomId, GroupDmWrapState.WAITING.name).filter { it.sealJson != null }.forEach { ledger ->
            val seal = runCatching { Event.fromJson(ledger.sealJson!!) }.getOrNull()
            if (seal == null || !Evidence.allows(EcdhPurpose.SEAL, seal.pubKey, seal, context.roomId)) {
                dao.upsertWrap(ledger.copy(state = GroupDmWrapState.UNREADABLE))
                changed = true
                return@forEach
            }
            val kept = dao.getConversationKey(context.roomId, seal.pubKey.lowercase())
            if (kept == null) {
                val roundId = GroupEcdhPayload.roundId(context.roomId, context.generation, seal.pubKey)
                if (dao.getRound(roundId) == null) {
                    GroupEcdhManager.ensureRound(database, context, seal.pubKey, EcdhPurpose.SEAL, ref = ledger.id)
                    changed = true
                }
                return@forEach
            }
            val rumor = GroupDmSeal.open(seal, kept.conversationKey.hexToByteArray())
            if (rumor == null) {
                dao.upsertWrap(ledger.copy(state = GroupDmWrapState.UNREADABLE))
            } else if (DisappearingMessages.expirationOf(rumor.tags, rumor.createdAt)?.let { DisappearingMessages.isExpired(it, Clock.System.now().epochSeconds) } == true) {
                // NIP-40: already gone. Not filed, and the ledger keeps the wrap's id but not its
                // words, so a re-sync does not open it again (docs/disappearing-messages.md, D6).
                dao.upsertWrap(ledger.copy(state = GroupDmWrapState.OPENED, wrapJson = "", sealJson = null))
            } else {
                file(database, context.roomId, rumor, wrapId = ledger.id)
                dao.upsertWrap(ledger.copy(state = GroupDmWrapState.OPENED))
            }
            changed = true
        }
        return changed
    }

    /**
     * A rumor into its conversation, once: the same rumor in two wraps is one message. A rumor
     * that is not kind 14 is filed as one the screen cannot show yet, not dropped.
     */
    private suspend fun file(database: DedunDatabase, roomId: HexKey, rumor: Event, wrapId: HexKey) {
        val inserted = database.groupDmDao().insertMessage(
            GroupDmMessage(
                rumorId = rumor.id,
                chatRoomId = roomId,
                conversationId = GroupDmConversation.idOf(rumor, roomId),
                senderPublicKey = rumor.pubKey,
                rumorJson = rumor.toJson(),
                createdAt = Instant.fromEpochSeconds(rumor.createdAt),
                outgoing = false,
                wrapId = wrapId,
            )
        )
        if (inserted > 0) logger.i { "Filed a message of kind ${rumor.kind} to ${roomId.take(8)}" }
    }

    /**
     * A device whose member is no longer one of [chatRoomId]'s admins forgets the room's mail:
     * its kept conversation keys, what it opened and the rounds in flight, and says so in the
     * room (D14). Cooperation, not enforcement -- what was read stays read -- and it is the
     * device's own: the group cut it off from new mail the moment the removal landed (D4).
     * Returns whether it forgot anything.
     */
    suspend fun forgetIfRemoved(database: DedunDatabase, chatRoomId: HexKey, userPublicKey: HexKey): Boolean {
        val admins = RoomAdmins.of(database, chatRoomId)?.admins ?: return false
        if (userPublicKey in admins) return false
        val dao = database.groupDmDao()
        val holds = dao.getConversationKeys(chatRoomId).isNotEmpty() || dao.getMessages(chatRoomId).isNotEmpty() ||
            dao.getAllOpenRounds().any { it.chatRoomId == chatRoomId } || dao.newestWrapCreatedAt(chatRoomId) != null
        if (!holds) return false
        dao.wipeRoom(chatRoomId)
        database.scrubFreedPages()
        database.chatMessageDao().upsert(
            com.machankura.dedun.compose.database.model.ChatMessage(
                senderPublicKey = userPublicKey,
                isUserMessage = true,
                giftWrapPayloadId = null,
                marmotGroupEventId = null,
                marmotInnerEventId = null,
                chatRoomId = chatRoomId,
                content = "You are no longer an admin, so this device forgot the group's direct messages",
                messageType = com.machankura.dedun.compose.database.model.ChatMessage.TYPE_GROUP_DM_FORGOTTEN,
            )
        )
        logger.i { "Forgot ${chatRoomId.take(8)}'s direct messages: no longer an admin" }
        return true
    }

    /**
     * What [ledger] waits on: how many more admins have to answer its open round, and which
     * current admins have not. Read from public rows only, so a screen can show it.
     */
    suspend fun waitingOn(database: DedunDatabase, roomId: HexKey, ledger: GroupDmWrap): Pair<Int, List<HexKey>> {
        val key = FrostSigningManager.completedKey(database, roomId) ?: return 0 to emptyList()
        val shares = ShareGenerations.active(database, key)
        val roomAdmins = RoomAdmins.of(database, roomId)?.admins.orEmpty()
        val admins = shares.signerIds.filterKeys { it in roomAdmins }
        val wrap = runCatching { Event.fromJson(ledger.wrapJson) }.getOrNull() ?: return 0 to emptyList()
        val point = ledger.sealJson?.let { runCatching { Event.fromJson(it).pubKey }.getOrNull() } ?: wrap.pubKey
        val round = database.groupDmDao().getRound(GroupEcdhPayload.roundId(roomId, shares.generation, point))
            ?.takeIf { it.state == GroupEcdhRoundState.OPEN }
        val answered = round?.let { r -> database.groupDmDao().getPartials(r.id).filter { it.verified }.map { it.participantId }.toSet() }.orEmpty()
        val needed = (key.threshold - answered.size).coerceAtLeast(0)
        return needed to admins.filterValues { it !in answered }.keys.sorted()
    }
}
