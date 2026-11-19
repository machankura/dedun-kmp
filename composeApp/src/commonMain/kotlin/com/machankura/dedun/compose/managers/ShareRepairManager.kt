package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.vitorpamplona.crypto.random.RandomInstance
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip44Encryption.Nip44
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.crypto.frost.FrostReducedEnrollment
import fr.acinq.bitcoin.crypto.frost.ReducedEnrollmentMessage
import fr.acinq.bitcoin.crypto.frost.ReducedShareAggregation
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.ShareRepairMessage
import com.machankura.dedun.compose.database.model.ShareRepairSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.database.model.types.ShareRepairStage
import com.machankura.dedun.compose.database.model.types.ShareStanding
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.frost.ShareRepairEvents

/**
 * Re-enrollment: brings a participant id a refresh left stale back to the
 * generation the group signs with, by reduced FROST enrollment in repair mode
 * (docs/frost-share-refresh.md, Phase 10).
 *
 * The target gets its point on the NEW polynomial, which its old device never
 * had -- so a repair after a refresh is how a member comes back without bringing
 * back anything a lost or removed device still holds. Two cases share this code:
 * - **10a**, the same identity, which missed the refresh;
 * - **10b**, a new identity at the id, which the group binds with a signed key
 *   state first ([GroupKeyStateManager.proposeRebind]). Until that binding is on
 *   file, a request waits in [ShareRepairStage.AWAITING_BINDING]: a repair to an
 *   identity the group has not bound is a repair to whoever asked.
 *
 * t live helpers run it. Their round-1.1 shares and round-1.2 sums are raw
 * additive shares, so each travels encrypted with NIP-44 between the sender's and
 * the recipient's ChillDKG host keys -- confidential to the two of them, and
 * authenticated by the same token. The target checks the result against the
 * public share the group signed (never one the helpers supply) and proves it
 * holds it with a Schnorr signature by the share over the session id; every
 * device checks that proof against the same public share before marking the id
 * live.
 */
object ShareRepairManager {
    private val logger = Logger.withTag("ShareRepairManager")

    /**
     * Asks to bring back [targetId] -- this device's own stale id when null -- at
     * the generation the group signs with, with the first t live ids as helpers.
     */
    suspend fun requestRepair(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        nostrPrivateKey: ByteArray,
        key: DkgSession,
        targetId: Int? = null,
    ): ShareRepairSession {
        require(key.stage == DkgRitualStage.COMPLETE) { "Ceremony ${key.id} has not produced a key" }
        val shares = ShareGenerations.active(database, key)
        val id = targetId ?: shares.roster.firstOrNull { it.memberPublicKey == userPublicKey }?.participantId
            ?: throw IllegalStateException("This identity holds no id in the key; name the id to be repaired")
        val entry = shares.roster.firstOrNull { it.participantId == id } ?: throw IllegalStateException("Id $id is not in the key")
        check(entry.standing == ShareStanding.STALE) { "Id $id is ${entry.standing.name.lowercase()}, and only a stale id is repaired" }
        val helpers = shares.roster.filter { it.standing == ShareStanding.LIVE }.map { it.participantId }.sorted().take(key.threshold)
        check(helpers.size >= key.threshold) { "Only ${helpers.size} members hold live shares; a repair needs ${key.threshold}" }
        val hostPublicKey = ChillDkgRitualManager.deriveHostPublicKey(nostrPrivateKey).value.toHex()

        val session = ShareRepairSession(
            id = RandomInstance.bytes(32).toHex(),
            chatRoomId = localChatRoom.chatRoom.id,
            dkgSessionId = key.id,
            generation = shares.generation,
            targetId = id,
            targetPublicKey = userPublicKey,
            targetHostPublicKey = hostPublicKey,
            helperIds = helpers.joinToString(","),
            userPublicKey = userPublicKey,
            stage = if (isBound(entry, userPublicKey, hostPublicKey)) ShareRepairStage.COLLECTING else ShareRepairStage.AWAITING_BINDING,
            sessionRandom = RandomInstance.bytes(32).toHex(),
        )
        database.shareRepairDao().upsert(session)
        announce(database, session, ChatMessage.TYPE_SHARE_REPAIR_REQUESTED, "asked to be brought back into the group's signing.", userPublicKey)
        broadcast(
            database, localChatRoom, userPublicKey, ShareRepairEvents.REQUEST, "",
            arrayOf(
                arrayOf(ShareRepairEvents.SESSION_TAG, session.id),
                arrayOf(ShareRepairEvents.KEY_TAG, key.id),
                arrayOf(ShareRepairEvents.GENERATION_TAG, shares.generation.toString()),
                arrayOf(ShareRepairEvents.TARGET_TAG, id.toString()),
                arrayOf(ShareRepairEvents.HELPERS_TAG, session.helperIds),
                arrayOf(ShareRepairEvents.HOST_KEY_TAG, hostPublicKey),
            )
        )
        return session
    }

    private fun isBound(entry: ShareGenerations.RosterEntry, memberPublicKey: HexKey, hostPublicKey: HexKey): Boolean =
        entry.memberPublicKey == memberPublicKey && entry.hostPublicKey.equals(hostPublicKey, ignoreCase = true)

    /** Feeds one inbound repair message in and advances. Safe to call twice. */
    suspend fun processRepairPayload(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        innerEvent: Event,
        userPublicKey: HexKey,
        nostrPrivateKey: ByteArray,
    ) {
        val sessionId = ShareRepairEvents.parseSessionId(innerEvent.tags) ?: return
        val session = if (innerEvent.kind == ShareRepairEvents.REQUEST) {
            acceptRequest(database, localChatRoom, innerEvent, sessionId, userPublicKey)
        } else {
            database.shareRepairDao().getSessionById(sessionId)
        } ?: return
        if (session.stage == ShareRepairStage.FAILED || session.stage == ShareRepairStage.COMPLETE) return

        record(database, session, innerEvent)
        advance(database, localChatRoom, session.id, nostrPrivateKey)
    }

    private suspend fun acceptRequest(database: DedunDatabase, localChatRoom: LocalChatRoom, event: Event, sessionId: String, userPublicKey: HexKey): ShareRepairSession? {
        database.shareRepairDao().getSessionById(sessionId)?.let { return it }
        val tag = { name: String -> ShareRepairEvents.value(event.tags, name) }
        val key = tag(ShareRepairEvents.KEY_TAG)?.let { database.dkgSessionDao().getSessionById(it) }?.takeIf { it.stage == DkgRitualStage.COMPLETE } ?: return null
        val generation = tag(ShareRepairEvents.GENERATION_TAG)?.toIntOrNull() ?: return null
        val targetId = tag(ShareRepairEvents.TARGET_TAG)?.toIntOrNull() ?: return null
        val hostKey = tag(ShareRepairEvents.HOST_KEY_TAG)?.takeIf { it.length == 66 }?.lowercase() ?: return null
        val helpers = tag(ShareRepairEvents.HELPERS_TAG)?.split(",")?.map { it.trim().toIntOrNull() ?: return null } ?: return null
        val shares = ShareGenerations.active(database, key)
        val entry = shares.roster.firstOrNull { it.participantId == targetId }

        val reason = when {
            shares.generation != generation -> "it repairs into generation $generation, and this device is at ${shares.generation}"
            entry == null -> "id $targetId is not in the key"
            entry.standing != ShareStanding.STALE -> "id $targetId is ${entry.standing.name.lowercase()}"
            helpers != helpers.sorted() || helpers.toSet().size != helpers.size -> "the helpers are not a set"
            helpers.size < key.threshold -> "${helpers.size} helpers cannot carry a ${key.threshold}-of-${key.participantCount} key"
            targetId in helpers -> "the target cannot help repair itself"
            !shares.roster.filter { it.standing == ShareStanding.LIVE }.map { it.participantId }.containsAll(helpers) -> "a helper holds no live share"
            else -> null
        }
        if (reason != null) {
            logger.w("Ignoring repair $sessionId: $reason")
            return null
        }
        val session = ShareRepairSession(
            id = sessionId,
            chatRoomId = localChatRoom.chatRoom.id,
            dkgSessionId = key.id,
            generation = generation,
            targetId = targetId,
            targetPublicKey = event.pubKey,
            targetHostPublicKey = hostKey,
            helperIds = helpers.joinToString(","),
            userPublicKey = userPublicKey,
            stage = if (isBound(entry!!, event.pubKey, hostKey)) ShareRepairStage.COLLECTING else ShareRepairStage.AWAITING_BINDING,
            sessionRandom = RandomInstance.bytes(32).toHex(),
        )
        database.shareRepairDao().upsert(session)
        announce(
            database, session, ChatMessage.TYPE_SHARE_REPAIR_REQUESTED,
            if (session.stage == ShareRepairStage.AWAITING_BINDING) {
                "asked to be brought back into the group's signing under a new identity. The group has to bind them to the id first."
            } else {
                "asked to be brought back into the group's signing."
            },
            event.pubKey
        )
        return session
    }

    private suspend fun record(database: DedunDatabase, session: ShareRepairSession, event: Event) {
        when (event.kind) {
            ShareRepairEvents.SHARE, ShareRepairEvents.SIGMA -> {
                val to = ShareRepairEvents.value(event.tags, ShareRepairEvents.TO_TAG) ?: return
                // Only what is addressed here can be opened here.
                if (to != session.userPublicKey) return
                database.shareRepairDao().upsert(ShareRepairMessage(session.id, event.pubKey, event.kind, to, event.content))
            }
            ShareRepairEvents.DONE -> {
                if (event.pubKey != session.targetPublicKey) return
                database.shareRepairDao().upsert(ShareRepairMessage(session.id, event.pubKey, event.kind, "", event.content))
            }
        }
    }

    /** Repairs waiting on a binding the group has now signed. Called when a key state's bindings are applied. */
    suspend fun resumeAwaitingBinding(database: DedunDatabase, dkgSessionId: String) {
        database.shareRepairDao().getSessionsForKey(dkgSessionId)
            .filter { it.stage == ShareRepairStage.AWAITING_BINDING }
            .forEach { session ->
                val room = database.chatRoomDao().findChatRoomById(session.chatRoomId) ?: return@forEach
                bindingCheck(database, session)
                // No private key here to go further; the next message, or the owner's
                // approval, takes the next step.
                logger.i("Repair ${session.id} has its binding: ${database.shareRepairDao().getSessionById(session.id)?.stage} in ${room.chatRoom.id}")
            }
    }

    /** Moves an awaiting session on once its target is bound. Returns the session as it now stands. */
    private suspend fun bindingCheck(database: DedunDatabase, session: ShareRepairSession): ShareRepairSession {
        if (session.stage != ShareRepairStage.AWAITING_BINDING) return session
        val key = database.dkgSessionDao().getSessionById(session.dkgSessionId) ?: return session
        val entry = ShareGenerations.at(database, key, session.generation)?.roster?.firstOrNull { it.participantId == session.targetId } ?: return session
        if (!isBound(entry, session.targetPublicKey, session.targetHostPublicKey)) return session
        return update(database, session) { it.copy(stage = ShareRepairStage.COLLECTING) }
    }

    private suspend fun advance(database: DedunDatabase, localChatRoom: LocalChatRoom, sessionId: String, nostrPrivateKey: ByteArray) {
        var session = database.shareRepairDao().getSessionById(sessionId) ?: return
        session = bindingCheck(database, session)
        if (session.stage != ShareRepairStage.COLLECTING) return

        try {
            val key = database.dkgSessionDao().getSessionById(session.dkgSessionId) ?: return
            val shares = ShareGenerations.at(database, key, session.generation) ?: return
            val table = shares.publicShares ?: return
            val thresholdPublicKey = PublicKey(ByteVector(requireNotNull(key.thresholdPublicKey).hexToByteArray()))
            val helpers = session.helperIdList()
            val helperIds = helpers.map { it.toUInt() }
            val n = key.participantCount
            val t = key.threshold
            val sid = ByteVector32(session.id)
            val hostSecretKey = ChillDkgRitualManager.deriveHostSecretKey(nostrPrivateKey)
            val myId = shares.roster.firstOrNull { it.memberPublicKey == session.userPublicKey && it.standing == ShareStanding.LIVE }?.participantId
            val rosterById = shares.roster.associateBy { it.participantId }

            // ---- every device: the target's proof makes the id live here ----
            database.shareRepairDao().getMessagesByKind(session.id, ShareRepairEvents.DONE).firstOrNull()?.let { done ->
                val proof = runCatching { ByteVector64(done.payload) }.getOrNull()
                val expected = table[session.targetId]
                if (proof == null || !Crypto.verifySignatureSchnorr(sid, proof, expected.xOnly())) {
                    throw IllegalStateException("the repaired member's proof does not match the group's public share")
                }
                markLive(database, key, session)
                complete(database, session, "was brought back into the group's signing.", session.targetPublicKey)
                return
            }

            // ---- a helper ----
            if (myId != null && myId in helpers && session.userPublicKey != session.targetPublicKey) {
                if (session.helpApprovedAt == null) {
                    if (!session.approvalRequested) {
                        update(database, session) { it.copy(approvalRequested = true) }
                        announce(database, session, ChatMessage.TYPE_SHARE_REPAIR_APPROVAL_NEEDED, "Your approval is needed to help bring a member back into the group's signing.", session.userPublicKey)
                    }
                    return
                }
                val secretShare = shares.secretShare ?: throw IllegalStateException("this device holds no share of generation ${session.generation}")
                val rows = FrostReducedEnrollment.generateShares(ByteVector32(session.sessionRandom), secretShare, thresholdPublicKey, helperIds, myId.toUInt(), session.targetId.toUInt(), n, t, sid).right
                    ?: throw IllegalStateException("round 1.1 refused this device's inputs")
                for (j in helpers.filter { it > myId }) {
                    val recipient = rosterById.getValue(j)
                    if (sent(database, session, ShareRepairEvents.SHARE, recipient.memberPublicKey)) continue
                    val payload = seal(rows.messageFor(j.toUInt()), hostSecretKey, recipient.hostPublicKey)
                    publishOwn(database, localChatRoom, session, ShareRepairEvents.SHARE, recipient.memberPublicKey, payload)
                }
                if (!sent(database, session, ShareRepairEvents.SIGMA, session.targetPublicKey)) {
                    val inbox = database.shareRepairDao().getMessagesByKind(session.id, ShareRepairEvents.SHARE)
                        .filter { it.recipientPublicKey == session.userPublicKey }
                        .mapNotNull { message ->
                            val sender = shares.roster.firstOrNull { it.memberPublicKey == message.senderPublicKey } ?: return@mapNotNull null
                            if (sender.participantId !in helpers || sender.participantId >= myId) return@mapNotNull null
                            sender.participantId.toUInt() to open(message.payload, hostSecretKey, sender.hostPublicKey)
                        }
                        .toMap()
                    if (inbox.size < helpers.count { it < myId }) return
                    val aggregated = FrostReducedEnrollment.aggregateShares(rows.kept, inbox, thresholdPublicKey, helperIds, myId.toUInt(), session.targetId.toUInt(), n, t, sid).right
                    when (aggregated) {
                        is ReducedShareAggregation.Aggregated -> {
                            val payload = seal(aggregated.message, hostSecretKey, session.targetHostPublicKey)
                            publishOwn(database, localChatRoom, session, ShareRepairEvents.SIGMA, session.targetPublicKey, payload)
                        }
                        is ReducedShareAggregation.HelperAtFault -> throw IllegalStateException("helper ${aggregated.helperId} disagreed on the repair's parameters")
                        null -> throw IllegalStateException("round 1.2 refused this device's inputs")
                    }
                }
                return
            }

            // ---- the target ----
            if (session.userPublicKey == session.targetPublicKey) {
                val sigmas = database.shareRepairDao().getMessagesByKind(session.id, ShareRepairEvents.SIGMA)
                    .filter { it.recipientPublicKey == session.userPublicKey }
                    .mapNotNull { message ->
                        val sender = shares.roster.firstOrNull { it.memberPublicKey == message.senderPublicKey } ?: return@mapNotNull null
                        if (sender.participantId !in helpers) return@mapNotNull null
                        sender.participantId.toUInt() to open(message.payload, hostSecretKey, sender.hostPublicKey)
                    }
                    .toMap()
                if (sigmas.size < helpers.size) return
                // Checked against the public share the group signed into this
                // generation's table -- never one the helpers supply.
                val expected = table[session.targetId]
                val repaired = FrostReducedEnrollment.generateSecretShare(sigmas, thresholdPublicKey, helperIds, session.targetId.toUInt(), n, t, sid, expected)
                val share = repaired.right ?: throw IllegalStateException("the repaired share does not match the group's public share: ${repaired.left?.message}")
                storeShare(database, key, session, share)
                val proof = Crypto.signSchnorr(sid, share, null)
                broadcast(database, localChatRoom, session.userPublicKey, ShareRepairEvents.DONE, proof.toHex(), arrayOf(arrayOf(ShareRepairEvents.SESSION_TAG, session.id)))
                markLive(database, key, session)
                complete(database, session, "was brought back into the group's signing.", session.targetPublicKey)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.e("Repair $sessionId failed", e)
            update(database, session) { it.copy(stage = ShareRepairStage.FAILED, failureReason = e.message) }
            announce(database, session, ChatMessage.TYPE_SHARE_REPAIR_FAILED, "Bringing a member back into the group's signing failed: ${e.message}", session.targetPublicKey)
        }
    }

    /** Approves helping with a repair, and moves on. */
    suspend fun approve(database: DedunDatabase, localChatRoom: LocalChatRoom, sessionId: String, nostrPrivateKey: ByteArray) {
        val session = database.shareRepairDao().getSessionById(sessionId) ?: return
        if (session.helpApprovedAt != null) return
        update(database, session) { it.copy(helpApprovedAt = Clock.System.now()) }
        advance(database, localChatRoom, sessionId, nostrPrivateKey)
    }

    /** Whether this device's owner is being asked to help with [session]. */
    suspend fun isAwaitingApproval(database: DedunDatabase, session: ShareRepairSession): Boolean {
        if (session.stage != ShareRepairStage.COLLECTING || session.helpApprovedAt != null) return false
        val key = database.dkgSessionDao().getSessionById(session.dkgSessionId) ?: return false
        val shares = ShareGenerations.at(database, key, session.generation) ?: return false
        val myId = shares.roster.firstOrNull { it.memberPublicKey == session.userPublicKey && it.standing == ShareStanding.LIVE }?.participantId
        return myId != null && myId in session.helperIdList()
    }

    // ---- storage ---------------------------------------------------------------------------------------------------

    private suspend fun storeShare(database: DedunDatabase, key: DkgSession, session: ShareRepairSession, share: PrivateKey) {
        val row = database.keyShareDao().getGeneration(key.id, session.generation)
            ?: throw IllegalStateException("generation ${session.generation} is not on this device")
        val now = Clock.System.now()
        database.keyShareDao().upsert(row.copy(secretShare = share.value.toHex(), updatedAt = now))
    }

    private suspend fun markLive(database: DedunDatabase, key: DkgSession, session: ShareRepairSession) {
        val member = database.keyShareDao().getMembers(key.id, session.generation).firstOrNull { it.participantId == session.targetId } ?: return
        if (member.standing == ShareStanding.LIVE) return
        database.keyShareDao().upsert(listOf(member.copy(standing = ShareStanding.LIVE, updatedAt = Clock.System.now())))
    }

    private suspend fun complete(database: DedunDatabase, session: ShareRepairSession, line: String, actor: HexKey) {
        val now = database.shareRepairDao().getSessionById(session.id) ?: session
        if (now.stage == ShareRepairStage.COMPLETE) return
        update(database, now) { it.copy(stage = ShareRepairStage.COMPLETE) }
        announce(database, now, ChatMessage.TYPE_SHARE_REPAIRED, line, actor)
    }

    private suspend fun update(database: DedunDatabase, session: ShareRepairSession, change: (ShareRepairSession) -> ShareRepairSession): ShareRepairSession {
        val current = database.shareRepairDao().getSessionById(session.id) ?: session
        val now = Clock.System.now()
        val updated = change(current).copy(updatedAt = now, savedAt = now)
        database.shareRepairDao().upsert(updated)
        return updated
    }

    private suspend fun sent(database: DedunDatabase, session: ShareRepairSession, kind: Kind, to: HexKey): Boolean =
        database.shareRepairDao().getMessagesByKind(session.id, kind).any { it.senderPublicKey == session.userPublicKey && it.recipientPublicKey == to }

    private suspend fun publishOwn(database: DedunDatabase, localChatRoom: LocalChatRoom, session: ShareRepairSession, kind: Kind, to: HexKey, payload: String) {
        broadcast(
            database, localChatRoom, session.userPublicKey, kind, payload,
            arrayOf(arrayOf(ShareRepairEvents.SESSION_TAG, session.id), arrayOf(ShareRepairEvents.TO_TAG, to))
        )
        database.shareRepairDao().upsert(ShareRepairMessage(session.id, session.userPublicKey, kind, to, payload))
    }

    // ---- the pairwise channel ------------------------------------------------------------------------------------

    /** NIP-44 between this device's host key and [toHostPublicKey]: only the two of them hold the conversation key. */
    private fun seal(message: ReducedEnrollmentMessage, hostSecretKey: PrivateKey, toHostPublicKey: HexKey): String =
        Nip44.encrypt("${message.share.toHex()}:${message.paramsHash.toHex()}", hostSecretKey.value.toByteArray(), xOnly(toHostPublicKey)).encodePayload()

    private fun open(payload: String, hostSecretKey: PrivateKey, fromHostPublicKey: HexKey): ReducedEnrollmentMessage {
        val plain = Nip44.decrypt(payload, hostSecretKey.value.toByteArray(), xOnly(fromHostPublicKey))
        val (share, hash) = plain.split(":").takeIf { it.size == 2 } ?: throw IllegalStateException("an unreadable repair message")
        return ReducedEnrollmentMessage(ByteVector32(share), ByteVector32(hash))
    }

    /** The x coordinate of a compressed host key, which is all NIP-44's ECDH reads. */
    private fun xOnly(hostPublicKey: HexKey): ByteArray = hostPublicKey.hexToByteArray().copyOfRange(1, 33)

    // ---- transport and transcript --------------------------------------------------------------------------------

    private suspend fun broadcast(database: DedunDatabase, localChatRoom: LocalChatRoom, author: HexKey, kind: Kind, content: String, tags: Array<Array<String>>) {
        var createdAt = Clock.System.now().epochSeconds
        var id = EventHasher.hashId(pubKey = author, createdAt = createdAt, kind = kind, tags = tags, content = content)
        var attempts = 0
        while (attempts < 16 && database.marmotInnerEventDao().getById(id) != null) {
            createdAt += 1
            id = EventHasher.hashId(pubKey = author, createdAt = createdAt, kind = kind, tags = tags, content = content)
            attempts += 1
        }
        database.marmotInnerEventDao().upsert(
            MarmotInnerEvent(id = id, publicKey = author, kind = kind, createdAt = Instant.fromEpochSeconds(createdAt), tags = tags, content = content, chatRoomId = localChatRoom.chatRoom.id)
        )
    }

    private suspend fun announce(database: DedunDatabase, session: ShareRepairSession, messageType: String, content: String, actor: HexKey) {
        database.chatMessageDao().upsert(
            ChatMessage(
                senderPublicKey = actor,
                isUserMessage = actor == session.userPublicKey,
                giftWrapPayloadId = null,
                marmotGroupEventId = null,
                marmotInnerEventId = null,
                chatRoomId = session.chatRoomId,
                content = content,
                messageType = messageType,
            )
        )
    }
}
