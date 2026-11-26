package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.FrostSigningItem
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.InboxKeyCeremony
import com.machankura.dedun.compose.database.model.InboxSigning
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.inbox.InboxAnnouncement
import com.machankura.dedun.compose.nostr.inbox.InboxReplyManifest
import com.machankura.dedun.compose.nostr.inbox.InboxSeal
import com.machankura.dedun.compose.nostr.inbox.InboxWire
import com.machankura.dedun.compose.database.model.InboxKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import kotlin.time.Clock

/**
 * What a device declines, out loud, when the group is asked to sign something about its inbox
 * (docs/group-inbox.md, D5): the announcement of its key (10044) and of where its mail goes
 * (10050).
 *
 * The signer has no allowlist, so without this a peer's 10044 reached the member as "Event of
 * kind 10044" and could be signed whatever key it named -- including one a removed admin made,
 * or one nobody in the group holds, either of which hands the group's mail to someone the
 * group did not choose. Dedun signs only what it can check.
 *
 * Checked in the four places every refusal sits in [FrostSigningManager]: on arrival, again at
 * approval, as the decline's reason, and (for a session filed before this existed) at approval
 * through the same call.
 */
object InboxRefusal {
    const val KEY_NOT_MADE = "Dedun doesn't sign an inbox key the group's admins didn't make together"
    const val KEY_NOT_NEWEST = "Dedun doesn't sign an inbox key the group has since replaced"
    const val KEY_NOT_ADMINS = "Dedun doesn't sign an inbox key made by anyone but the group's current admins"
    const val KEY_SHAPE = "Dedun doesn't sign an inbox key announcement it would not write"
    const val RELAYS_SHAPE = "Dedun doesn't sign a list of inbox relays a stranger could not reach"

    // A reply (D12): every signer reads it before signing it.
    const val REPLY_UNREADABLE = "Dedun doesn't sign a sealed message it can't read"
    const val REPLY_NO_KEY = "This device holds no inbox key for this group"
    const val REPLY_MISMATCH = "Dedun doesn't sign a reply whose copies don't all say the same thing to the people it names"
    const val REPLY_WRONG_KEY = "Dedun doesn't sign a reply sealed to a key its recipient didn't announce"
    const val REPLY_BLANK = "Dedun doesn't sign a reply with nothing in it"
    const val REPLY_TIME = "Dedun doesn't sign a reply dated outside the last two days"

    private const val TWO_DAYS = 2 * 24 * 60 * 60L
    private const val CLOCK_SLACK = 10 * 60L

    /**
     * Whether any of [events] is one this refusal is about. A seal only when it names an inbox
     * key in its `n` tag, as every reply does: a seal without one is the group writing by
     * threshold ECDH, which `GroupDmRefusal` reads (docs/group-threshold-ecdh.md, D13).
     */
    fun involves(events: List<Event>): Boolean =
        events.any { it.kind == InboxAnnouncement.KEY_KIND || it.kind == InboxAnnouncement.RELAYS_KIND || isReplySeal(it) }

    private fun isReplySeal(event: Event): Boolean = event.kind == InboxSeal.KIND && InboxSeal.encryptionKeyOf(event.tags) != null

    /**
     * Why this device declines to sign [events] in [localChatRoom] as [userPublicKey] sees it, or
     * null when it would sign them.
     */
    suspend fun of(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        events: List<Event>,
        /** A reply's sealed manifest, as its proposal carried it (D11). */
        manifest: String? = null,
        now: Long = Clock.System.now().epochSeconds,
    ): String? {
        if (events.any(::isReplySeal)) {
            return replyRefusal(database, localChatRoom.chatRoom.id, userPublicKey, events, manifest, now)
        }
        for (event in events) {
            when (event.kind) {
                InboxAnnouncement.RELAYS_KIND ->
                    if (InboxAnnouncement.groupRelaysOf(event) == null) return RELAYS_SHAPE
                InboxAnnouncement.KEY_KIND -> {
                    val key = InboxAnnouncement.groupKeyOf(event) ?: return KEY_SHAPE
                    keyRefusal(database, localChatRoom, userPublicKey, key)?.let { return it }
                }
            }
        }
        return null
    }

    /**
     * D5's three checks on the key a 10044 names: it is what a ceremony in this room made, the
     * newest one, and that ceremony was run over the admins the room has now.
     */
    private suspend fun keyRefusal(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey, key: HexKey): String? {
        val roomId = localChatRoom.chatRoom.id
        val completed = database.inboxKeyDao().getCompletedCeremonies(roomId, userPublicKey)
        val maker = completed.firstOrNull { keyOf(database, it) == key }
        if (maker == null) {
            // The coordinator asks for the announcement the moment its own copy of the key is
            // made, which is routinely before this device has finished the same ceremony. Not a
            // refusal yet: the session waits, approval checks again, and the ceremony finishing
            // here asks again ([FrostSigningManager.resumeInboxAnnouncements]).
            val running = database.inboxKeyDao().getRunningCeremoniesIn(roomId).any { it.userPublicKey == userPublicKey }
            return if (running) null else KEY_NOT_MADE
        }
        if (completed.any { it.generation > maker.generation }) return KEY_NOT_NEWEST
        val admins = RoomAdmins.of(database, roomId)?.admins ?: return null
        if (admins != maker.participants().toSet()) return KEY_NOT_ADMINS
        return null
    }

    /** What a signer reads a reply with: the key that opened its manifest, the manifest, and the one rumor. */
    data class ReadReply(val key: InboxKey, val manifest: InboxReplyManifest, val rumor: Event)

    /**
     * D12's checks on a batch of seals. A signer reads the reply before signing it: it opens
     * the manifest, checks every seal is sealed from the announced generation to the key its
     * recipient announced (or their identity), opens every one, and refuses unless they all hold
     * the same rumor, written by the group to exactly the people the manifest names.
     */
    private suspend fun replyRefusal(database: DedunDatabase, roomId: HexKey, userPublicKey: HexKey, events: List<Event>, manifest: String?, now: Long): String? {
        if (events.any { it.kind != InboxSeal.KIND }) return REPLY_UNREADABLE
        val read = when (val result = readReply(database, roomId, userPublicKey, events, manifest)) {
            is Read.Refused -> return result.reason
            is Read.Ok -> result.reply
        }
        if (read.rumor.content.isBlank()) return REPLY_BLANK
        if (events.any { it.createdAt < now - TWO_DAYS - CLOCK_SLACK || it.createdAt > now + CLOCK_SLACK }) return REPLY_TIME
        return null
    }

    private sealed interface Read {
        data class Ok(val reply: ReadReply) : Read
        data class Refused(val reason: String) : Read
    }

    private suspend fun readReply(database: DedunDatabase, roomId: HexKey, userPublicKey: HexKey, events: List<Event>, manifest: String?): Read {
        val sealed = manifest ?: return Read.Refused(REPLY_UNREADABLE)
        val keys = database.inboxKeyDao().getKeys(roomId, userPublicKey).filter { it.secretKey != null }
        val (key, opened) = keys.firstNotNullOfOrNull { k ->
            InboxReplyManifest.open(sealed, k.secretKey!!.hexToByteArray())?.let { k to it }
        } ?: return Read.Refused(REPLY_NO_KEY)
        if (opened.generation != key.generation || opened.seals.size != events.size) return Read.Refused(REPLY_UNREADABLE)
        events.forEach { seal ->
            if (!seal.pubKey.equals(roomId, ignoreCase = true) || seal.tags.size != 1 || InboxSeal.encryptionKeyOf(seal.tags) != key.publicKey) {
                return Read.Refused(REPLY_UNREADABLE)
            }
        }
        opened.seals.forEachIndexed { index, entry ->
            val keyIsRight = if (index == opened.groupCopy) {
                entry.recipient.equals(roomId, ignoreCase = true) && entry.key == key.publicKey && entry.announcement == null
            } else {
                !entry.recipient.equals(roomId, ignoreCase = true) && when (val json = entry.announcement) {
                    null -> entry.key == entry.recipient
                    else -> Event.fromJsonOrNull(json)?.let { announcement ->
                        announcement.verify() && announcement.pubKey == entry.recipient && InboxAnnouncement.announcedKeyOf(announcement) == entry.key
                    } == true
                }
            }
            if (!keyIsRight) return Read.Refused(REPLY_WRONG_KEY)
        }
        val secret = key.secretKey!!.hexToByteArray()
        val rumors = events.mapIndexed { index, seal ->
            InboxSeal.rumorInside(seal.content, InboxWire.conversationKey(secret, opened.seals[index].key), roomId)
                ?: return Read.Refused(REPLY_MISMATCH)
        }
        if (rumors.map { it.id }.distinct().size != 1) return Read.Refused(REPLY_MISMATCH)
        val rumor = rumors.first()
        if (rumor.kind != 14) return Read.Refused(REPLY_UNREADABLE)
        val named = rumor.tags.filter { it.size >= 2 && it[0] == "p" }.map { it[1].lowercase() }.distinct().sorted()
        val manifestRecipients = opened.seals.filterIndexed { index, _ -> index != opened.groupCopy }.map { it.recipient.lowercase() }.distinct().sorted()
        if (named != manifestRecipients || opened.seals.size != manifestRecipients.size + 1) return Read.Refused(REPLY_MISMATCH)
        return Read.Ok(ReadReply(key, opened, rumor))
    }

    /**
     * What the approval screen says about a reply (D12): its words, who it goes to, and what this
     * device could not check -- a recipient who has announced no key may have an app that cannot
     * open a message from a group (D13).
     */
    suspend fun notes(database: DedunDatabase, chatRoomId: String, userPublicKey: HexKey, sessionId: String, events: List<Event>): List<String> {
        if (events.none(::isReplySeal)) return emptyList()
        val manifest = database.groupInboxDao().getSigning(sessionId)?.manifest
        val reply = (readReply(database, chatRoomId, userPublicKey, events, manifest) as? Read.Ok)?.reply
            ?: return listOf("A private reply from the group's inbox, which this device cannot read")
        val recipients = reply.manifest.seals.filterIndexed { index, _ -> index != reply.manifest.groupCopy }
        val names = database.profileDao().getProfileByPublicKeys(recipients.map { it.recipient })
            .associate { it.publicKey to it.humanReadableNameOrPubkey() }
        return buildList {
            add("The group would say: " + reply.rumor.content)
            add("To " + recipients.joinToString(", ") { names[it.recipient] ?: it.recipient.take(8) })
            recipients.filter { it.announcement == null }.forEach {
                add((names[it.recipient] ?: it.recipient.take(8)) + " has announced no inbox key, so their app may not be able to open a message from a group")
            }
        }
    }

    /** The manifest a resent proposal has to carry again, as it came. */
    suspend fun carriedTagsFor(database: DedunDatabase, sessionId: String): Array<Array<String>> =
        database.groupInboxDao().getSigning(sessionId)?.manifest?.let { arrayOf(InboxReplyManifest.tag(it)) } ?: emptyArray()

    /** The key [ceremony] made, recomputing it on a watcher's device that missed it at the time. */
    private suspend fun keyOf(database: DedunDatabase, ceremony: InboxKeyCeremony): HexKey? =
        ceremony.inboxPublicKey ?: InboxKeyRitualManager.keyWatched(database, ceremony)

    /**
     * Whether this device can sign [items] on its owner's behalf (D5): a rotation's 10044 alone,
     * naming a key this device holds the secret of, in a room whose quorum has announced an
     * inbox before. The decision to have an inbox was asked; keeping it is what a rotation is.
     */
    suspend fun vouchesFor(database: DedunDatabase, localChatRoom: LocalChatRoom, session: FrostSigningSession, items: List<FrostSigningItem>): Boolean {
        val events = items.mapNotNull { Event.fromJsonOrNull(it.unsignedEventJson) }
        val announcement = events.singleOrNull()?.takeIf { it.kind == InboxAnnouncement.KEY_KIND } ?: return false
        if (of(database, localChatRoom, session.userPublicKey, events) != null) return false
        val key = InboxAnnouncement.groupKeyOf(announcement) ?: return false
        val roomId = localChatRoom.chatRoom.id
        // Never on the strength of a ceremony still running: the refusal lets one wait, and
        // vouching is for a key this device has.
        val held = database.inboxKeyDao().getKeys(roomId, session.userPublicKey)
        if (held.none { it.publicKey == key && it.secretKey != null }) return false
        val announcedBefore = database.groupSignedEventDao().getByChatRoomIdAndKind(roomId, InboxAnnouncement.KEY_KIND)
        return announcedBefore.any { it.publicKey == roomId && InboxAnnouncement.groupKeyOf(it.kind, it.tags, it.content) != key }
    }

    /** Keeps why [sessionId] was refused, so the decline can say it. */
    suspend fun remember(database: DedunDatabase, sessionId: String, chatRoomId: String, refusal: String?, manifest: String? = null) {
        val existing = database.groupInboxDao().getSigning(sessionId)
        database.groupInboxDao().upsertSigning(
            (existing ?: InboxSigning(sessionId = sessionId, chatRoomId = chatRoomId)).copy(
                refusal = refusal ?: existing?.refusal,
                manifest = manifest ?: existing?.manifest,
            )
        )
    }

    suspend fun reasonFor(database: DedunDatabase, sessionId: String): String? =
        database.groupInboxDao().getSigning(sessionId)?.refusal
}
