package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.vitorpamplona.crypto.random.RandomInstance
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.ByteVector64
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.crypto.dkg.chill.ChillDKG
import fr.acinq.secp256k1.ChilldkgFault
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import com.machankura.dedun.compose.ExperimentalFeatures
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.InboxKey
import com.machankura.dedun.compose.database.model.InboxKeyCeremony
import com.machankura.dedun.compose.database.model.InboxKeyCeremonyMessage
import com.machankura.dedun.compose.database.model.InboxKeyCeremonyStage
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.Relays
import com.machankura.dedun.compose.nostr.inbox.InboxKeyRitualEvents
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.machankura.dedun.compose.nostr.inbox.InboxWire

/**
 * The group's inbox key ceremony (docs/group-inbox.md, D2, D3 and D4): ChillDKG at threshold 1
 * over the room's admins, which leaves every one of them holding the same secret `e` and the
 * group a public key `E` nobody chose alone.
 *
 * The rounds are [ChillDkgRitualManager]'s, on kinds and tables of their own, with three
 * differences:
 *
 * - **The participants are the room's admins**, exactly. Every device checks the proposal's
 *   `p` tags against its own reading of the room's admin policy, and a disagreement ends the
 *   ceremony out loud with the difference named.
 * - **Nobody is asked.** Being an admin is the decision to hold the key; deciding to *have* an
 *   inbox is the group signing its announcement (D5). An admin's device takes part on arrival.
 * - **The output is a whole secret**, written once to [InboxKey]. Nothing that looks for a
 *   signing key reads that table.
 *
 * Every message is an MLS inner event in the group's own room. Every member receives them all;
 * ChillDKG encrypts each contribution to its recipients' host keys, so a member who is not an
 * admin learns `E` and nothing else, and files three lines in the transcript.
 */
object InboxKeyRitualManager {
    private val logger = Logger.withTag("InboxKeyRitualManager")

    /**
     * A hash input, spelled the house way. Bound to the room so the host key an admin uses for
     * one group's inbox says nothing about another's, and to nothing else, so the nsec alone
     * recovers it.
     */
    private const val HOST_KEY_TAG = "mantra/inbox-key/host-key/v1"

    const val MINIMUM_ADMINS = 2

    const val ABANDONED = "Abandoned by an admin"

    fun deriveHostSecretKey(nostrPrivateKey: ByteArray, chatRoomId: String): PrivateKey =
        PrivateKey(Crypto.sha256(HOST_KEY_TAG.encodeToByteArray() + nostrPrivateKey + chatRoomId.hexToByteArray()))

    fun deriveHostPublicKey(nostrPrivateKey: ByteArray, chatRoomId: String): PublicKey =
        ChillDKG.hostPublicKey(deriveHostSecretKey(nostrPrivateKey, chatRoomId))

    /**
     * Why [userPublicKey] cannot open an inbox key ceremony in [localChatRoom] now, or null if
     * they can. Read by the screens, so a button that could only fail is never offered.
     */
    suspend fun refusal(database: DedunDatabase, localChatRoom: LocalChatRoom, userPublicKey: HexKey): String? {
        if (!ExperimentalFeatures.groupInbox) return "This build has no group inbox"
        val room = localChatRoom.chatRoom
        if (room.mlsGroupState == null || room.leftGroupAt != null) return "Only a group's own room can have an inbox"
        val key = FrostSigningManager.completedKey(database, room.id)
            ?: return "The group has no key to announce an inbox with"
        val admins = RoomAdmins.of(database, room.id)?.admins ?: return "This device cannot read who the group's admins are"
        if (userPublicKey !in admins) return "Only an admin can set up the group's inbox"
        if (admins.size < MINIMUM_ADMINS) return "The group needs at least $MINIMUM_ADMINS admins for an inbox"
        when (val hold = ShareRefreshManager.rotationHold(database, localChatRoom, key)) {
            is RotationHold.Running, is RotationHold.AdminChangeInFlight, is RotationHold.Enrolling -> return hold.reason.replaceFirstChar { it.uppercase() }
            else -> Unit
        }
        return null
    }

    /** The generation a new ceremony in [chatRoomId] makes: one past any this device has seen. */
    suspend fun nextGeneration(database: DedunDatabase, chatRoomId: String, userPublicKey: HexKey): Int {
        val keys = database.inboxKeyDao().getKeys(chatRoomId, userPublicKey).maxOfOrNull { it.generation }
        val ceremony = database.inboxKeyDao().getLatestCeremony(chatRoomId, userPublicKey)
            ?.takeIf { it.stage == InboxKeyCeremonyStage.COMPLETE }?.generation
        return listOfNotNull(keys, ceremony).maxOrNull()?.plus(1) ?: 0
    }

    /**
     * Opens a ceremony over the room's current admins, making this device its coordinator. A
     * ceremony already running in the room is handed back instead of a second one. Throws with
     * [refusal]'s reason when the room cannot have one.
     */
    suspend fun propose(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        userPublicKey: HexKey,
        nostrPrivateKey: ByteArray,
        /** Where the group's mail should arrive, for the first generation's 10050 (D6). */
        relays: List<NormalizedRelayUrl> = Relays.DefaultDMRelayList,
    ): InboxKeyCeremony {
        val roomId = localChatRoom.chatRoom.id
        database.inboxKeyDao().getRunningCeremoniesIn(roomId).firstOrNull { it.userPublicKey == userPublicKey }?.let { return it }

        refusal(database, localChatRoom, userPublicKey)?.let { throw IllegalStateException(it) }
        val admins = RoomAdmins.of(database, roomId)!!.admins

        val generation = nextGeneration(database, roomId, userPublicKey)
        val ceremony = InboxKeyCeremony(
            id = RandomInstance.bytes(32).toHex(),
            chatRoomId = roomId,
            generation = generation,
            announceRelays = relays.takeIf { generation == 0 }?.joinToString(",") { it.url },
            coordinatorPublicKey = userPublicKey,
            userPublicKey = userPublicKey,
            participantPublicKeys = InboxKeyCeremony.formatParticipants(admins),
            hostPublicKey = deriveHostPublicKey(nostrPrivateKey, roomId).value.toHex(),
            round1Random = RandomInstance.bytes(32).toHex(),
            round2AuxRandom = RandomInstance.bytes(32).toHex(),
        )
        database.inboxKeyDao().upsertCeremony(ceremony)
        announceStarted(database, ceremony)
        logger.i("Proposing inbox key ceremony ${ceremony.id.take(8)} at generation ${ceremony.generation} over ${admins.size} admins")

        broadcast(
            database, localChatRoom, ceremony, InboxKeyRitualEvents.PROPOSAL, "",
            proposal = true,
        )
        advance(database, localChatRoom, ceremony.id, nostrPrivateKey)
        return database.inboxKeyDao().getCeremony(ceremony.id)!!
    }

    /**
     * Feeds one inbound ceremony message in and moves the ceremony as far as it will go. Safe to
     * call twice with the same message: every write is keyed and every step recomputed.
     */
    suspend fun process(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        innerEvent: Event,
        userPublicKey: HexKey,
        nostrPrivateKey: ByteArray,
    ) {
        val sessionId = InboxKeyRitualEvents.parseSessionId(innerEvent.tags) ?: run {
            logger.w("Inbox key message ${innerEvent.id.take(8)} names no session; dropping")
            return
        }

        val ceremony = if (innerEvent.kind == InboxKeyRitualEvents.PROPOSAL) {
            accept(database, localChatRoom, innerEvent, sessionId, userPublicKey, nostrPrivateKey)
        } else {
            // Relays and MLS hand messages back in no particular order; a round can arrive
            // before its proposal. It is already stored as an inner event, and [accept]
            // replays it once the proposal turns up.
            database.inboxKeyDao().getCeremony(sessionId)
        } ?: return

        if (!ceremony.isRunning) return
        if (!record(database, ceremony, innerEvent)) return
        if (ceremony.isParticipant()) advance(database, localChatRoom, ceremony.id, nostrPrivateKey)
    }

    /**
     * Moves every inbox key ceremony [userPublicKey] takes part in as far as its stored
     * messages allow, for a ceremony left mid-round by a crash. Returns how many it looked at.
     */
    suspend fun resumeRunning(database: DedunDatabase, userPublicKey: HexKey, nostrPrivateKey: ByteArray): Int {
        val running = database.inboxKeyDao().getRunningCeremonies(userPublicKey).filter { it.isParticipant() }
        running.forEach { ceremony ->
            val room = database.chatRoomDao().findChatRoomById(ceremony.chatRoomId) ?: return@forEach
            advance(database, room, ceremony.id, nostrPrivateKey)
        }
        return running.size
    }

    /**
     * Ends a running ceremony for everyone in it. Only an admin it names can: a member who was
     * never in it walking away is not the ceremony failing. Returns whether it was ended.
     */
    suspend fun abandon(database: DedunDatabase, localChatRoom: LocalChatRoom, sessionId: String): Boolean {
        val ceremony = database.inboxKeyDao().getCeremony(sessionId) ?: return false
        if (!ceremony.isRunning || !ceremony.isParticipant()) return false
        fail(database, ceremony, ABANDONED, ceremony.userPublicKey)
        broadcast(database, localChatRoom, ceremony, InboxKeyRitualEvents.FAILURE, ABANDONED)
        return true
    }

    // ---- arrival -------------------------------------------------------------------------------

    private suspend fun accept(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        innerEvent: Event,
        sessionId: String,
        userPublicKey: HexKey,
        nostrPrivateKey: ByteArray,
    ): InboxKeyCeremony? {
        database.inboxKeyDao().getCeremony(sessionId)?.let { return it }
        val roomId = localChatRoom.chatRoom.id

        if (localChatRoom.chatRoom.mlsGroupState == null) {
            logger.w("Inbox key proposal ${sessionId.take(8)} outside a Marmot room; dropping")
            return null
        }
        val generation = InboxKeyRitualEvents.parseGeneration(innerEvent.tags)
        val named = InboxKeyRitualEvents.parseParticipants(innerEvent.tags)
        if (generation == null || named == null || InboxKeyRitualEvents.parseThreshold(innerEvent.tags) != InboxKeyRitualEvents.THRESHOLD) {
            logger.w("Inbox key proposal ${sessionId.take(8)} is malformed; dropping")
            return null
        }
        val participants = (named + innerEvent.pubKey).distinct().sorted()

        val base = InboxKeyCeremony(
            id = sessionId,
            chatRoomId = roomId,
            generation = generation,
            coordinatorPublicKey = innerEvent.pubKey,
            userPublicKey = userPublicKey,
            participantPublicKeys = InboxKeyCeremony.formatParticipants(participants),
        )

        // A member who is not named only watches: it files the lines, and holds nothing.
        val mine = if (userPublicKey in participants) {
            base.copy(
                hostPublicKey = deriveHostPublicKey(nostrPrivateKey, roomId).value.toHex(),
                round1Random = RandomInstance.bytes(32).toHex(),
                round2AuxRandom = RandomInstance.bytes(32).toHex(),
            )
        } else {
            base
        }
        database.inboxKeyDao().upsertCeremony(mine)
        announceStarted(database, mine)

        // D4: the participants are the admins, exactly, as this device reads them. A
        // ceremony over anyone else would hand the group's mail to whoever the coordinator
        // put in it, or leave an admin without it. An admin the proposal left out says so as
        // loudly as a named one does.
        val admins = RoomAdmins.of(database, roomId)?.admins
        val mismatch = when {
            admins == null -> null // nothing to compare with; the others will
            admins.toSet() != participants.toSet() -> describeMismatch(admins, participants)
            else -> null
        }
        if (mismatch != null && (userPublicKey in participants || userPublicKey in admins.orEmpty())) {
            logger.w("Inbox key ceremony ${sessionId.take(8)}: $mismatch")
            fail(database, mine, mismatch, userPublicKey)
            broadcast(database, localChatRoom, mine, InboxKeyRitualEvents.FAILURE, mismatch)
            return database.inboxKeyDao().getCeremony(sessionId)
        }

        replayStored(database, localChatRoom, mine)
        return database.inboxKeyDao().getCeremony(sessionId)
    }

    private fun describeMismatch(admins: Set<HexKey>, participants: List<HexKey>): String {
        val missing = admins - participants.toSet()
        val extra = participants.toSet() - admins
        return buildList {
            if (missing.isNotEmpty()) add("leaves out ${missing.size} admin${if (missing.size == 1) "" else "s"} (${missing.sorted().joinToString { it.take(8) }})")
            if (extra.isNotEmpty()) add("names ${extra.size} who ${if (extra.size == 1) "is not an admin" else "are not admins"} (${extra.sorted().joinToString { it.take(8) }})")
        }.joinToString(" and ", prefix = "The inbox key ceremony ")
    }

    /** Every message of [ceremony] that arrived before its proposal, read back out of the room's inner events. */
    private suspend fun replayStored(database: DedunDatabase, localChatRoom: LocalChatRoom, ceremony: InboxKeyCeremony) {
        val stored = database.marmotInnerEventDao()
            .getByChatRoomAndKinds(chatRoomId = ceremony.chatRoomId, kinds = (InboxKeyRitualEvents.ALL - InboxKeyRitualEvents.PROPOSAL).toList())
            .filter { InboxKeyRitualEvents.parseSessionId(it.tags) == ceremony.id }
        stored.forEach { row ->
            val event = Event(row.id, row.publicKey, row.createdAt.epochSeconds, row.kind, row.tags, row.content, "")
            if (!record(database, ceremony, event)) return
        }
    }

    /**
     * Files one message. Returns false when it ends the ceremony. Round messages count only
     * from a participant, the coordinator's aggregates only from the coordinator: taking either
     * from anyone else would let any member stall the ceremony by getting a bogus one in first.
     */
    private suspend fun record(database: DedunDatabase, ceremony: InboxKeyCeremony, innerEvent: Event): Boolean {
        val participants = ceremony.participants()
        val sender = innerEvent.pubKey
        when (innerEvent.kind) {
            InboxKeyRitualEvents.PROPOSAL -> Unit
            InboxKeyRitualEvents.HOST_KEY, InboxKeyRitualEvents.ROUND_1, InboxKeyRitualEvents.ROUND_2 -> {
                if (sender !in participants) return true
                store(database, ceremony, sender, innerEvent.kind, innerEvent.content)
            }
            InboxKeyRitualEvents.COORDINATOR_ROUND_1 -> {
                if (sender != ceremony.coordinatorPublicKey) return true
                store(database, ceremony, sender, innerEvent.kind, innerEvent.content)
            }
            InboxKeyRitualEvents.CERTIFICATE -> {
                if (sender != ceremony.coordinatorPublicKey) return true
                store(database, ceremony, sender, innerEvent.kind, innerEvent.content)
                // A member who only watches holds no secret, but every message the
                // coordinator aggregated is public in the room, so it can aggregate them too
                // and learn the key the admins made -- which is what lets it check the
                // group's announcement of that key before signing it (D5).
                if (!ceremony.isParticipant()) {
                    val current = database.inboxKeyDao().getCeremony(ceremony.id) ?: ceremony
                    if (current.isRunning) {
                        val watched = runCatching { watchedKey(database, current) }.getOrNull()
                        database.inboxKeyDao().upsertCeremony(
                            current.copy(stage = InboxKeyCeremonyStage.COMPLETE, inboxPublicKey = watched, updatedAt = Clock.System.now())
                        )
                        announce(database, current, ChatMessage.TYPE_INBOX_KEY_COMPLETE, "The group's admins finished making its inbox key.", current.coordinatorPublicKey)
                    }
                }
            }
            InboxKeyRitualEvents.FAILURE -> {
                if (sender !in participants && sender !in (RoomAdmins.of(database, ceremony.chatRoomId)?.admins ?: emptySet())) return true
                fail(database, ceremony, innerEvent.content.ifBlank { ABANDONED }, sender)
                return false
            }
        }
        return true
    }

    private suspend fun store(database: DedunDatabase, ceremony: InboxKeyCeremony, sender: HexKey, kind: Kind, payload: String) {
        val inserted = database.inboxKeyDao().insertMessage(InboxKeyCeremonyMessage(ceremony.id, sender, kind, payload))
        if (inserted > 0) {
            database.inboxKeyDao().getCeremony(ceremony.id)?.let {
                database.inboxKeyDao().upsertCeremony(it.copy(lastMessageAt = Clock.System.now(), updatedAt = Clock.System.now()))
            }
        }
    }

    // ---- the rounds ----------------------------------------------------------------------------

    /**
     * Takes every step the stored messages allow, stopping at the first one still waiting on
     * somebody. Each step asks whether its output exists rather than what the stage says, so a
     * re-entry repeats no work and skips none.
     */
    private suspend fun advance(database: DedunDatabase, localChatRoom: LocalChatRoom, sessionId: String, nostrPrivateKey: ByteArray) {
        val start = database.inboxKeyDao().getCeremony(sessionId) ?: return
        if (!start.isRunning || !start.isParticipant()) return
        val roomId = start.chatRoomId
        val hostSecret = deriveHostSecretKey(nostrPrivateKey, roomId)
        val threshold = InboxKeyRitualEvents.THRESHOLD

        try {
            if (own(database, start, InboxKeyRitualEvents.HOST_KEY) == null) {
                publishOwn(database, localChatRoom, start, InboxKeyRitualEvents.HOST_KEY, requireNotNull(start.hostPublicKey))
            }
            val hostPublicKeys = hostPublicKeys(database, start) ?: return

            val step1 = ChillDKG.participantStep1(hostSecret, hostPublicKeys, threshold, ByteVector32(requireNotNull(start.round1Random)))
            if (own(database, start, InboxKeyRitualEvents.ROUND_1) == null) {
                publishOwn(database, localChatRoom, start, InboxKeyRitualEvents.ROUND_1, step1.message.toHex())
            }
            moveTo(database, start, InboxKeyCeremonyStage.ROUND_1)

            if (start.isCoordinator() && coordinatorMessage(database, start, InboxKeyRitualEvents.COORDINATOR_ROUND_1) == null) {
                val pmsgs1 = ordered(database, start, InboxKeyRitualEvents.ROUND_1) ?: return
                val cmsg1 = coordinatorStep1(pmsgs1, hostPublicKeys).message.toHex()
                publishOwn(database, localChatRoom, start, InboxKeyRitualEvents.COORDINATOR_ROUND_1, cmsg1)
            }
            val cmsg1 = coordinatorMessage(database, start, InboxKeyRitualEvents.COORDINATOR_ROUND_1) ?: return

            // The check that stops a coordinator substituting a key the admins never
            // contributed to: participantStep2 faults on a mismatch, and the catch below fails
            // the ceremony for everyone.
            val step2 = ChillDKG.participantStep2(hostSecret, step1.state, ByteVector(cmsg1.hexToByteArray()), ByteVector32(requireNotNull(start.round2AuxRandom)))
            step2.fault.raiseIfFaulty("round 2")
            if (own(database, start, InboxKeyRitualEvents.ROUND_2) == null) {
                publishOwn(database, localChatRoom, start, InboxKeyRitualEvents.ROUND_2, step2.certEqSignature.toHex())
            }
            moveTo(database, start, InboxKeyCeremonyStage.ROUND_2)

            if (start.isCoordinator() && coordinatorMessage(database, start, InboxKeyRitualEvents.CERTIFICATE) == null) {
                val pmsgs2 = ordered(database, start, InboxKeyRitualEvents.ROUND_2) ?: return
                val pmsgs1 = ordered(database, start, InboxKeyRitualEvents.ROUND_1) ?: return
                val certified = ChillDKG.coordinatorFinalize(coordinatorStep1(pmsgs1, hostPublicKeys).state, pmsgs2.map { ByteVector64(it) }, threshold)
                certified.fault.raiseIfFaulty("certificate")
                publishOwn(database, localChatRoom, start, InboxKeyRitualEvents.CERTIFICATE, certified.certificate.toHex())
            }
            val certificate = coordinatorMessage(database, start, InboxKeyRitualEvents.CERTIFICATE) ?: return

            val output = ChillDKG.participantFinalize(step2.state, ByteVector(certificate.hexToByteArray()), hostPublicKeys.size, threshold)
            output.fault.raiseIfFaulty("finalize")

            // D2's premise, checked on every run rather than trusted from Phase 0: at t = 1
            // the share is the whole secret, so it has to be the discrete log of the key.
            val secret = requireNotNull(output.secretShare) { "the ceremony left no secret" }
            val thresholdKey = requireNotNull(output.thresholdPublicKey) { "the ceremony left no key" }
            check(secret.publicKey() == thresholdKey) { "the inbox key's secret is not its key's: the ceremony was not at threshold 1" }
            val secretBytes = secret.value.toByteArray()
            val inboxKey = InboxWire.publicKeyOf(secretBytes)

            database.inboxKeyDao().upsertKey(
                InboxKey(
                    chatRoomId = roomId,
                    userPublicKey = start.userPublicKey,
                    generation = start.generation,
                    publicKey = inboxKey,
                    secretKey = secretBytes.toHex(),
                    recoveryData = output.recovery?.toHex(),
                    ceremonyId = start.id,
                )
            )
            val done = (database.inboxKeyDao().getCeremony(sessionId) ?: start)
                .copy(stage = InboxKeyCeremonyStage.COMPLETE, inboxPublicKey = inboxKey, updatedAt = Clock.System.now())
            database.inboxKeyDao().upsertCeremony(done)
            announce(
                database, done, ChatMessage.TYPE_INBOX_KEY_COMPLETE,
                "The group's admins made its inbox key. It reaches nobody until the group announces it.",
                done.coordinatorPublicKey,
            )
            logger.i("Inbox key ceremony ${sessionId.take(8)} complete: generation ${done.generation}")
            InboxFollowThrough.onKeyMade(database, localChatRoom, done)
            FrostSigningManager.resumeInboxAnnouncements(database, localChatRoom, done.userPublicKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.e("Inbox key ceremony ${sessionId.take(8)} failed", e)
            val reason = e.message ?: "The inbox key ceremony failed"
            val current = database.inboxKeyDao().getCeremony(sessionId) ?: start
            fail(database, current, reason, current.userPublicKey)
            broadcast(database, localChatRoom, current, InboxKeyRitualEvents.FAILURE, reason)
        }
    }

    /**
     * The key a ceremony made, as a member who only watched it can compute: the coordinator's
     * two aggregations need no secret, and every message they take is in the room. Null while
     * any is missing, or if they do not aggregate.
     */
    internal suspend fun keyWatched(database: DedunDatabase, ceremony: InboxKeyCeremony): HexKey? =
        runCatching { watchedKey(database, ceremony) }.getOrNull()?.also { key ->
            database.inboxKeyDao().getCeremony(ceremony.id)?.let { database.inboxKeyDao().upsertCeremony(it.copy(inboxPublicKey = key)) }
        }

    private suspend fun watchedKey(database: DedunDatabase, ceremony: InboxKeyCeremony): HexKey? {
        val hostPublicKeys = hostPublicKeys(database, ceremony) ?: return null
        val pmsgs1 = ordered(database, ceremony, InboxKeyRitualEvents.ROUND_1) ?: return null
        val pmsgs2 = ordered(database, ceremony, InboxKeyRitualEvents.ROUND_2) ?: return null
        val state = ChillDKG.coordinatorStep1(pmsgs1.map { ByteVector(it) }, hostPublicKeys, InboxKeyRitualEvents.THRESHOLD)
            .takeIf { it.fault.isOk }?.state ?: return null
        val certified = ChillDKG.coordinatorFinalize(state, pmsgs2.map { ByteVector64(it) }, InboxKeyRitualEvents.THRESHOLD)
            .takeIf { it.fault.isOk } ?: return null
        return certified.thresholdPublicKey?.xOnly()?.value?.toHex()
    }

    /** The participants' host keys, sorted, or null while any is outstanding. */
    private suspend fun hostPublicKeys(database: DedunDatabase, ceremony: InboxKeyCeremony): List<PublicKey>? {
        val participants = ceremony.participants()
        val hostKeys = database.inboxKeyDao().getMessages(ceremony.id)
            .filter { it.kind == InboxKeyRitualEvents.HOST_KEY && it.senderPublicKey in participants }
        if (hostKeys.size < participants.size) return null
        return hostKeys.map { it.payload.lowercase() }.sorted().map { PublicKey(ByteVector(it.hexToByteArray())) }
    }

    /** A round's messages, one per participant, in host-key order; null while any is missing. */
    private suspend fun ordered(database: DedunDatabase, ceremony: InboxKeyCeremony, kind: Kind): List<ByteArray>? {
        val messages = database.inboxKeyDao().getMessages(ceremony.id)
        val hostKeyOf = messages.filter { it.kind == InboxKeyRitualEvents.HOST_KEY }.associate { it.senderPublicKey to it.payload.lowercase() }
        val participants = ceremony.participants()
        val round = participants.map { participant ->
            val message = messages.firstOrNull { it.kind == kind && it.senderPublicKey == participant } ?: return null
            val hostKey = hostKeyOf[participant] ?: return null
            hostKey to message.payload
        }
        return round.sortedBy { it.first }.map { it.second.hexToByteArray() }
    }

    private fun coordinatorStep1(pmsgs1: List<ByteArray>, hostPublicKeys: List<PublicKey>) =
        ChillDKG.coordinatorStep1(pmsgs1.map { ByteVector(it) }, hostPublicKeys, InboxKeyRitualEvents.THRESHOLD)
            .also { it.fault.raiseIfFaulty("coordinator round 1") }

    private fun ChilldkgFault.raiseIfFaulty(step: String) {
        if (isOk) return
        val culprit = participantIndex?.let { " (participant $it)" } ?: ""
        val reason = when (code) {
            ChilldkgFault.FAULTY_COORDINATOR -> "the coordinator is faulty"
            ChilldkgFault.FAULTY_PARTICIPANT -> "a participant is faulty"
            ChilldkgFault.FAULTY_PARTICIPANT_OR_COORDINATOR -> "a participant or the coordinator is faulty"
            ChilldkgFault.UNKNOWN_FAULTY_PARTICIPANT_OR_COORDINATOR -> "an unidentified participant or the coordinator is faulty"
            ChilldkgFault.INVALID_INPUT -> "invalid input"
            else -> "unrecognised fault code $code"
        }
        throw IllegalStateException("The inbox key ceremony's $step failed: $reason$culprit")
    }

    private suspend fun own(database: DedunDatabase, ceremony: InboxKeyCeremony, kind: Kind) =
        database.inboxKeyDao().getMessage(ceremony.id, ceremony.userPublicKey, kind)

    private suspend fun coordinatorMessage(database: DedunDatabase, ceremony: InboxKeyCeremony, kind: Kind): String? =
        database.inboxKeyDao().getMessage(ceremony.id, ceremony.coordinatorPublicKey, kind)?.payload

    private suspend fun moveTo(database: DedunDatabase, ceremony: InboxKeyCeremony, stage: InboxKeyCeremonyStage) {
        val current = database.inboxKeyDao().getCeremony(ceremony.id) ?: return
        if (!current.isRunning || current.stage.ordinal >= stage.ordinal) return
        database.inboxKeyDao().upsertCeremony(current.copy(stage = stage, updatedAt = Clock.System.now()))
    }

    private suspend fun fail(database: DedunDatabase, ceremony: InboxKeyCeremony, reason: String, culprit: HexKey) {
        val current = database.inboxKeyDao().getCeremony(ceremony.id) ?: ceremony
        if (current.stage == InboxKeyCeremonyStage.FAILED) return
        database.inboxKeyDao().upsertCeremony(
            current.copy(stage = InboxKeyCeremonyStage.FAILED, failureReason = reason, culprit = culprit, updatedAt = Clock.System.now())
        )
        announce(database, current, ChatMessage.TYPE_INBOX_KEY_FAILED, "ended the inbox key ceremony: $reason. No key was made, and it is safe to run it again.", culprit)
    }

    // ---- the wire ------------------------------------------------------------------------------

    /** Sends one of this device's messages and records it, so its own is in every aggregation. */
    private suspend fun publishOwn(database: DedunDatabase, localChatRoom: LocalChatRoom, ceremony: InboxKeyCeremony, kind: Kind, content: String) {
        broadcast(database, localChatRoom, ceremony, kind, content)
        store(database, ceremony, ceremony.userPublicKey, kind, content)
    }

    /**
     * Queues one ceremony message as an MLS inner event for the room, the path chat messages
     * take. The proposal carries the admins as `p` tags, the participant set every device
     * rebuilds; nothing later needs them.
     */
    private suspend fun broadcast(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        ceremony: InboxKeyCeremony,
        kind: Kind,
        content: String,
        proposal: Boolean = false,
    ) {
        val tags = InboxKeyRitualEvents.assembleTags(
            sessionId = ceremony.id,
            generation = if (proposal) ceremony.generation else null,
            participants = if (proposal) ceremony.participants().filterNot { it == ceremony.userPublicKey } else emptyList(),
        )
        val createdAt = Clock.System.now().epochSeconds
        val id = EventHasher.hashId(ceremony.userPublicKey, createdAt, kind, tags, content)
        database.marmotInnerEventDao().upsert(
            MarmotInnerEvent(
                id = id,
                publicKey = ceremony.userPublicKey,
                kind = kind,
                createdAt = Instant.fromEpochSeconds(createdAt),
                tags = tags,
                content = content,
                chatRoomId = localChatRoom.chatRoom.id,
            )
        )
    }

    // ---- the transcript ------------------------------------------------------------------------

    private suspend fun announceStarted(database: DedunDatabase, ceremony: InboxKeyCeremony) {
        val n = ceremony.participants().size
        val text = if (ceremony.generation == 0) {
            "started making the group's inbox key with its $n admins. Every admin will hold it, and it finishes once all of them have taken part."
        } else {
            "started changing the group's inbox key with its $n admins. Every admin will hold the new one, and it finishes once all of them have taken part."
        }
        announce(database, ceremony, ChatMessage.TYPE_INBOX_KEY_STARTED, text, ceremony.coordinatorPublicKey)
    }

    private suspend fun announce(database: DedunDatabase, ceremony: InboxKeyCeremony, type: String, content: String, actor: HexKey) {
        database.chatMessageDao().upsert(
            ChatMessage(
                senderPublicKey = actor,
                isUserMessage = actor == ceremony.userPublicKey,
                giftWrapPayloadId = null,
                marmotGroupEventId = null,
                marmotInnerEventId = null,
                chatRoomId = ceremony.chatRoomId,
                content = content,
                messageType = type,
            )
        )
    }
}
