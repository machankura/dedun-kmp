package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip59Giftwrap.rumors.RumorAssembler
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlin.time.Clock
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.GroupInboxMessage
import com.machankura.dedun.compose.database.model.GroupInboxPublication
import com.machankura.dedun.compose.database.model.InboxKeyState
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.Relays
import com.machankura.dedun.compose.nostr.inbox.InboxAnnouncement
import com.machankura.dedun.compose.nostr.inbox.InboxConversation
import com.machankura.dedun.compose.nostr.inbox.InboxReplyManifest
import com.machankura.dedun.compose.nostr.inbox.InboxSeal
import com.machankura.dedun.compose.nostr.inbox.InboxWire
import com.machankura.dedun.compose.nostr.inbox.InboxWrap

/**
 * The group answering from its inbox (docs/group-inbox.md, D10 to D13).
 *
 * A reply is one rumor, authored by the group, sealed once per recipient and once to the group
 * itself, from the inbox key the group announced. The seals are what the quorum signs; nothing
 * leaves until it has. Then the device that proposed it wraps each signed seal and owes the
 * wraps to the recipients' relays, and the group's copy to the group's, which is how every
 * admin sees the reply.
 */
object InboxReplies {
    private val logger = Logger.withTag("InboxReplies")

    /** Who a seal is to and the key it is sealed with, and the 10044 that says so. */
    data class Recipient(val publicKey: HexKey, val encryptionKey: HexKey, val announcement: Event?)

    /** A reply ready to propose: the seals to sign, in order, and the manifest that explains them. */
    data class Built(val rumor: Event, val seals: List<EventTemplate<Event>>, val manifest: InboxReplyManifest)

    /**
     * Builds a reply from the group of [roomId] to [recipients], answering [replyTo], from the
     * inbox key [inboxSecret] of [generation].
     */
    fun build(
        roomId: HexKey,
        recipients: List<Recipient>,
        text: String,
        replyTo: HexKey?,
        inboxSecret: ByteArray,
        generation: Int,
        now: Long = TimeUtils.now(),
        sealTime: () -> Long = { TimeUtils.randomWithTwoDays() },
    ): Built {
        require(text.isNotBlank()) { "A reply needs something to say" }
        require(recipients.isNotEmpty()) { "A reply needs someone to go to" }
        val ordered = recipients.distinctBy { it.publicKey }.sortedBy { it.publicKey }
        val tags = buildList {
            ordered.forEach { add(arrayOf("p", it.publicKey)) }
            replyTo?.let { add(arrayOf("e", it, "", "reply")) }
        }.toTypedArray()
        val rumor = RumorAssembler.assembleRumor<Event>(pubKey = roomId, ev = EventTemplate(now, 14, tags, text))

        val inboxKey = InboxWire.publicKeyOf(inboxSecret)
        val entries = ordered.map { InboxReplyManifest.Entry(it.publicKey, it.encryptionKey, it.announcement?.toJson()) } +
            InboxReplyManifest.Entry(roomId, inboxKey, null)
        val seals = entries.map { InboxSeal.template(rumor, inboxSecret, it.key, sealTime()) }
        return Built(rumor, seals, InboxReplyManifest(generation, entries, groupCopy = entries.lastIndex))
    }

    /** The key a person reads encrypted mail with, as this device last heard: their 10044, or their identity. */
    suspend fun recipientOf(database: DedunDatabase, publicKey: HexKey): Recipient {
        val announcement = database.nostrEventDao().getNostrEventByPublicKeyAndKind(publicKey, InboxAnnouncement.KEY_KIND)
            ?.toEvent()?.takeIf { it.verify() }
        val key = announcement?.let { InboxAnnouncement.announcedKeyOf(it) }
        return Recipient(publicKey, key ?: publicKey, announcement.takeIf { key != null })
    }

    /** Whether [publicKey] has said, with a 10044, which key reads their mail (D13's warning when not). */
    suspend fun hasAnnouncedKey(database: DedunDatabase, publicKey: HexKey): Boolean =
        recipientOf(database, publicKey).announcement != null

    /**
     * Proposes a reply in [localChatRoom]'s conversation [conversationId], answering its newest
     * message, from the key the group has announced. Throws with a reason a person can read when
     * there is nothing to answer, or no announced key this device holds.
     */
    suspend fun propose(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        conversationId: String,
        text: String,
    ): FrostSigningSession {
        val roomId = localChatRoom.chatRoom.id
        val conversation = database.groupInboxDao().getConversation(roomId, conversationId)
        val newest = conversation.lastOrNull() ?: throw IllegalStateException("There is nothing here to reply to")
        // D14: sealed from the key the group announced, never a newer one it has not.
        val key = database.inboxKeyDao().getKeys(roomId, userPublicKey)
            .firstOrNull { it.state == InboxKeyState.ANNOUNCED && it.secretKey != null }
            ?: throw IllegalStateException("The group has announced no inbox key this device holds")
        val recipients = newest.participantList().map { recipientOf(database, it) }
        val built = build(
            roomId = roomId,
            recipients = recipients,
            text = text,
            replyTo = conversation.lastOrNull { !it.outgoing }?.rumorId,
            inboxSecret = key.secretKey!!.hexToByteArray(),
            generation = key.generation,
        )
        val sealed = built.manifest.seal(key.secretKey.hexToByteArray())
        val session = FrostSigningManager.proposeSigningBatch(
            database, localChatRoom, userPublicKey,
            events = built.seals,
            proposalTags = arrayOf(InboxReplyManifest.tag(sealed)),
        )
        InboxRefusal.remember(database, session.id, roomId, refusal = null, manifest = sealed)
        logger.i("Proposed a reply to ${recipients.size} in ${roomId.take(8)} as session ${session.id.take(8)}")
        return session
    }

    /**
     * What a signed reply leaves (D10), on every device that took part: on the device that
     * proposed it, a wrap per seal owed to its recipient's relays and the group's copy filed as
     * an outgoing message at once. Every other admin's device files the copy when it arrives.
     */
    suspend fun onSigned(database: DedunDatabase, session: FrostSigningSession, signedEvents: List<Event>) {
        // A reply's seals name the inbox key in an `n` tag; a seal without one is the group
        // writing by threshold ECDH, which `GroupDmSender` wraps (docs/group-threshold-ecdh.md).
        val seals = signedEvents.filter {
            it.kind == InboxSeal.KIND && it.pubKey.equals(session.chatRoomId, ignoreCase = true) && InboxSeal.encryptionKeyOf(it.tags) != null
        }
        if (seals.isEmpty() || !session.isCoordinator()) return
        wrapAndOwe(database, session, seals)
    }

    /**
     * Wraps [seals] again and owes them to relays: what a person does from the conversation when
     * the device that proposed a reply never got it out. New wraps of the same seals; the group's
     * side files them as the one message they are.
     */
    suspend fun sendAgain(database: DedunDatabase, sessionId: String): Boolean {
        val session = database.frostSigningSessionDao().getSessionById(sessionId) ?: return false
        val seals = database.groupSignedEventDao().getByChatRoomIdAndKind(session.chatRoomId, InboxSeal.KIND)
            .filter { it.frostSigningSessionId == sessionId }
            .map { it.toEvent() }
        if (seals.isEmpty()) return false
        wrapAndOwe(database, session, seals)
        return true
    }

    private suspend fun wrapAndOwe(database: DedunDatabase, session: FrostSigningSession, seals: List<Event>) {
        val roomId = session.chatRoomId
        val signing = database.groupInboxDao().getSigning(session.id) ?: run {
            logger.w("Signed reply ${session.id.take(8)} has no manifest here; not sending it")
            return
        }
        val keys = database.inboxKeyDao().getKeys(roomId, session.userPublicKey).filter { it.secretKey != null }
        val opened = keys.firstNotNullOfOrNull { key ->
            signing.manifest?.let { InboxReplyManifest.open(it, key.secretKey!!.hexToByteArray()) }?.let { key to it }
        } ?: run {
            logger.w("Signed reply ${session.id.take(8)}: no key here opens its manifest; not sending it")
            return
        }
        val (key, manifest) = opened
        // In the order the proposal fixed, which is the manifest's.
        val signedById = seals.associateBy { it.id }
        val ordered = database.frostSigningSessionDao().getItems(session.id).sortedBy { it.itemIndex }.map { signedById[it.eventId] }
        if (ordered.size != manifest.seals.size) return

        manifest.seals.forEachIndexed { index, entry ->
            val seal = ordered[index] ?: return@forEachIndexed
            val relays = if (index == manifest.groupCopy) {
                InboxAnnouncements.relaysOf(database, roomId)
            } else {
                relaysOf(database, entry.recipient)
            }
            val wrap = InboxWrap.wrap(seal, entry.recipient, entry.key, relays.firstOrNull())
            database.groupInboxDao().upsertPublication(
                GroupInboxPublication(
                    eventId = wrap.id,
                    chatRoomId = roomId,
                    kind = wrap.kind,
                    eventJson = wrap.toJson(),
                    relays = relays.joinToString(",") { it.url },
                    signingSessionId = session.id,
                    recipientPublicKey = entry.recipient,
                )
            )
        }

        // The group's copy, filed now so the reply is in the conversation before it comes back.
        val copy = ordered[manifest.groupCopy] ?: return
        val rumor = InboxSeal.rumorInside(copy.content, InboxReplyManifest.selfConversationKey(key.secretKey!!.hexToByteArray()), roomId) ?: return
        val participants = InboxConversation.participantsOf(rumor, roomId)
        database.groupInboxDao().insertMessage(
            GroupInboxMessage(
                chatRoomId = roomId,
                rumorId = rumor.id,
                wrapId = null,
                conversationId = InboxConversation.idOf(participants),
                participants = participants.joinToString(","),
                senderPublicKey = roomId,
                kind = rumor.kind,
                rumorJson = InboxSeal.rumorJson(rumor),
                createdAt = rumor.createdAt,
                generation = manifest.generation,
                outgoing = true,
                signingSessionId = session.id,
                readAt = Clock.System.now(),
            )
        )
    }

    /** Where [publicKey] reads private mail: their 10050, as this device last heard it. */
    private suspend fun relaysOf(database: DedunDatabase, publicKey: HexKey): List<NormalizedRelayUrl> =
        database.nostrEventDao().getNostrEventByPublicKeyAndKind(publicKey, InboxAnnouncement.RELAYS_KIND)
            ?.toEvent()?.let { InboxAnnouncement.announcedRelaysOf(it) }
            ?.ifEmpty { null }
            ?: Relays.DefaultDMRelayList
}
