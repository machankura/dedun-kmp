package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.vitorpamplona.crypto.random.RandomInstance
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import kotlin.time.Clock
import kotlin.time.Instant
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.GroupDmMessage
import com.machankura.dedun.compose.database.model.GroupDmOutbound
import com.machankura.dedun.compose.database.model.GroupDmWrapState
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.Relays
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.nostr.groupdm.EcdhPurpose
import com.machankura.dedun.compose.nostr.groupdm.Evidence
import com.machankura.dedun.compose.nostr.groupdm.GroupDmConversation
import com.machankura.dedun.compose.nostr.groupdm.GroupDmManifest
import com.machankura.dedun.compose.nostr.groupdm.GroupDmSeal

/**
 * The group writing to someone (D10 and D12 of docs/group-threshold-ecdh.md): one rumor, one
 * seal per recipient under the kept `conv(group, R)`, signed by the group in one batch, then
 * wrapped by the proposer's device under a key of its own and sent to each recipient's 10050.
 *
 * There is no copy to the group itself: that would be ECDH with the group's own npub, which D5
 * forbids. Every admin's device files the message as outgoing from the manifest instead.
 */
object GroupDmSender {
    private val logger = Logger.withTag("GroupDmSender")

    private const val TWO_DAYS = 2L * 24 * 60 * 60

    sealed interface Outcome {
        /** Proposed: the group's quorum signs it next. */
        data class Proposed(val sessionId: String) : Outcome

        /** A recipient's key is still being computed by the admins; try again when it is. */
        data class Preparing(val recipients: List<HexKey>) : Outcome

        /** Nothing signed by [recipient] is on this device, so no device may multiply by their key (D5). */
        data class NoEvidence(val recipient: HexKey) : Outcome

        /** This device's member is not a current admin holding a share. */
        data object NotAnAdmin : Outcome
    }

    /**
     * Something [recipient] signed that this device can check: a seal they sent the group, or
     * any event of theirs it holds. The smallest of them, since the manifest carries it.
     */
    suspend fun evidenceFor(database: DedunDatabase, roomId: HexKey, recipient: HexKey): Event? {
        val r = recipient.lowercase()
        val seals = database.groupDmDao().getWrapsInState(roomId, GroupDmWrapState.OPENED.name)
            .mapNotNull { it.sealJson?.let { json -> runCatching { Event.fromJson(json) }.getOrNull() } }
            .filter { it.pubKey == r }
        val own = listOf(10050, 0, 10002, 1).mapNotNull { kind ->
            database.nostrEventDao().getNostrEventByPublicKeyAndKind(r, kind)?.takeIf { it.sig.length == 128 }?.toEvent()
        }
        return (own + seals)
            .filter { Evidence.allows(EcdhPurpose.RECIPIENT, r, it, roomId) }
            .minByOrNull { it.toJson().length }
    }

    /** Where [recipient] receives messages: their 10050, or the publish set and the app's DM relays. */
    suspend fun relaysFor(database: DedunDatabase, recipient: HexKey): List<String> {
        val listed = database.nostrEventDao().getNostrEventByPublicKeyAndKind(recipient.lowercase(), GroupDmRelays.KIND)
            ?.let { GroupDmRelays.relaysIn(it.tags) }
        return listed?.takeIf { it.isNotEmpty() }
            ?: (Relays.eventPublishRelaySet.map { it.url } + Relays.DefaultDMRelayList.map { it.url }).distinct()
    }

    /**
     * Asks the group to send [text] to [recipients], answering [replyTo] if given. A recipient
     * whose key the admins have not computed yet has a round opened, and the answer is
     * [Outcome.Preparing]; the composer tries again once the round combines.
     */
    suspend fun send(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        nostrPrivateKey: ByteArray,
        recipients: List<HexKey>,
        text: String,
        replyTo: HexKey? = null,
        now: Long = Clock.System.now().epochSeconds,
    ): Outcome {
        require(text.isNotBlank()) { "A message has words in it" }
        val to = recipients.map { it.lowercase() }.distinct()
        require(to.isNotEmpty()) { "A message has a recipient" }
        val context = GroupEcdhManager.contextFor(database, localChatRoom, userPublicKey, nostrPrivateKey) ?: return Outcome.NotAnAdmin
        val roomId = context.roomId
        require(to.none { it == roomId }) { "The group does not write to itself (D5)" }

        val evidence = to.associateWith { r -> evidenceFor(database, roomId, r) ?: return Outcome.NoEvidence(r) }
        val keys = to.associateWith { database.groupDmDao().getConversationKey(roomId, it) }
        val missing = to.filter { keys[it] == null }
        if (missing.isNotEmpty()) {
            missing.forEach { r -> GroupEcdhManager.ensureRound(database, context, r, EcdhPurpose.RECIPIENT, evidence = evidence.getValue(r)) }
            GroupEcdhManager.advance(database, context)
            val still = missing.filter { database.groupDmDao().getConversationKey(roomId, it) == null }
            if (still.isNotEmpty()) return Outcome.Preparing(still)
        }

        val tags = to.map { arrayOf("p", it) } + listOfNotNull(replyTo?.let { arrayOf("e", it, "", "reply") })
        val rumor = GroupDmSeal.rumor(roomId, now, 14, tags.toTypedArray(), text)
        val seals = to.map { r ->
            val key = database.groupDmDao().getConversationKey(roomId, r)!!.conversationKey.hexToByteArray()
            GroupDmSeal.build(rumor, key, createdAt = now - randomBackdate())
        }
        val manifest = GroupDmManifest(to.map { GroupDmManifest.Seal(it, evidence.getValue(it)) }, replyTo)
        val session = FrostSigningManager.proposeSigningBatch(
            database = database,
            localChatRoom = localChatRoom,
            userPublicKey = userPublicKey,
            events = seals,
            key = context.key,
            proposalTags = manifest.toTags(context.sealToAdmins(manifest.encode())),
        )
        record(database, session, roomId, rumor, manifest)
        return Outcome.Proposed(session.id)
    }

    /** NIP-59's back-dating: up to two days, at random. */
    private fun randomBackdate(): Long {
        val bytes = RandomInstance.bytes(4)
        val v = ((bytes[0].toLong() and 0xff) shl 24) or ((bytes[1].toLong() and 0xff) shl 16) or
            ((bytes[2].toLong() and 0xff) shl 8) or (bytes[3].toLong() and 0xff)
        return v % TWO_DAYS
    }

    /**
     * Files a message the group is being asked to send, on any admin's device that has read it:
     * outgoing in its conversation, and one owed wrap per recipient, keyed by the seal's id,
     * waiting for the signature. Idempotent.
     */
    suspend fun record(database: DedunDatabase, session: FrostSigningSession, roomId: HexKey, rumor: Event, manifest: GroupDmManifest) {
        val dao = database.groupDmDao()
        dao.insertMessage(
            GroupDmMessage(
                rumorId = rumor.id,
                chatRoomId = roomId,
                conversationId = GroupDmConversation.idOf(rumor, roomId),
                senderPublicKey = roomId,
                rumorJson = rumor.toJson(),
                createdAt = Instant.fromEpochSeconds(rumor.createdAt),
                outgoing = true,
                signingSessionId = session.id,
                readAt = Clock.System.now(),
            )
        )
        val items = database.frostSigningSessionDao().getItems(session.id).sortedBy { it.itemIndex }
        items.forEachIndexed { i, item ->
            val recipient = manifest.seals.getOrNull(i)?.recipient ?: return@forEachIndexed
            dao.insertOutbound(
                GroupDmOutbound(
                    signingSessionId = session.id,
                    sealId = item.eventId,
                    chatRoomId = roomId,
                    recipient = recipient,
                    relays = relaysFor(database, recipient).joinToString(","),
                )
            )
        }
    }

    /**
     * A batch of seals the group just signed: the proposer's device wraps each for its recipient
     * and owes it to their relays. Other devices hold the same rows unwrapped, for Send again.
     */
    suspend fun onSigned(database: DedunDatabase, session: FrostSigningSession, signedEvents: List<Event>) {
        if (signedEvents.none { it.kind == SealEvent.KIND }) return
        if (!session.isCoordinator()) return
        wrapOwed(database, session.id, signedEvents)
    }

    /**
     * Send again, from any admin's device: wraps whatever this device has not wrapped, from the
     * signed seals every signer holds, and owes them again. Whether there was anything to send.
     */
    suspend fun sendAgain(database: DedunDatabase, signingSessionId: String): Boolean {
        val signed = database.groupDmDao().getOutbound(signingSessionId).mapNotNull { row ->
            database.groupSignedEventDao().getById(row.sealId)?.toEvent()
        }
        val rows = wrapOwed(database, signingSessionId, signed)
        database.groupDmDao().getOutbound(signingSessionId).filter { it.publishedAt == null && it.wrapJson != null }.forEach {
            database.groupDmDao().upsertOutbound(it.copy(attempts = 0, nextAttemptAt = null))
        }
        return rows > 0 || database.groupDmDao().getOutbound(signingSessionId).any { it.publishedAt == null }
    }

    private suspend fun wrapOwed(database: DedunDatabase, signingSessionId: String, signedEvents: List<Event>): Int {
        val dao = database.groupDmDao()
        var wrapped = 0
        dao.getOutbound(signingSessionId).filter { it.wrapJson == null }.forEach { row ->
            val signed = signedEvents.firstOrNull { it.id == row.sealId }?.takeIf { it.verify() } ?: return@forEach
            // Ordinary NIP-59: a fresh key of this device's, no group secret involved.
            val wrap = GiftWrapEvent.create(event = signed, recipientPubKey = row.recipient)
            dao.upsertOutbound(row.copy(wrapJson = wrap.toJson()))
            wrapped += 1
        }
        if (wrapped > 0) logger.i { "Wrapped $wrapped seal(s) of $signingSessionId for their recipients" }
        return wrapped
    }

    /**
     * Proposals of the group's messages this device held back while it computed a recipient's
     * key (D13, check 4): handed to the signing manager again now that a round has combined.
     */
    suspend fun retryHeld(database: DedunDatabase, context: GroupEcdhManager.RoomContext, nostrPrivateKey: ByteArray) {
        database.marmotInnerEventDao().getByChatRoomAndKinds(context.roomId, listOf(FrostSigningEvents.PROPOSAL))
            .filter { GroupDmManifest.isCarriedBy(it.tags) }
            .forEach { stored ->
                val sessionId = FrostSigningEvents.parseSessionId(stored.tags) ?: return@forEach
                if (database.frostSigningSessionDao().getSessionById(sessionId) != null) return@forEach
                FrostSigningManager.processSigningPayload(
                    database = database,
                    localChatRoom = context.room,
                    innerEvent = Event(stored.id, stored.publicKey, stored.createdAt.epochSeconds, stored.kind, stored.tags, stored.content, ""),
                    userPublicKey = context.userPublicKey,
                    nostrPrivateKey = nostrPrivateKey,
                )
            }
    }
}
