package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.vitorpamplona.crypto.random.RandomInstance
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import kotlin.time.Clock
import kotlin.time.Instant
import com.machankura.dedun.compose.cryptography.threshold.Dleq
import com.machankura.dedun.compose.cryptography.threshold.EcdhPartial
import com.machankura.dedun.compose.cryptography.threshold.NpubTweak
import com.machankura.dedun.compose.cryptography.threshold.ThresholdEcdh
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.GroupConversationKey
import com.machankura.dedun.compose.database.model.GroupDmWrapState
import com.machankura.dedun.compose.database.model.GroupEcdhPartial
import com.machankura.dedun.compose.database.model.GroupEcdhRound
import com.machankura.dedun.compose.database.model.GroupEcdhRoundState
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.groupdm.AdminSeal
import com.machankura.dedun.compose.nostr.groupdm.EcdhPurpose
import com.machankura.dedun.compose.nostr.groupdm.Evidence
import com.machankura.dedun.compose.nostr.groupdm.GroupDmWrap
import com.machankura.dedun.compose.nostr.groupdm.GroupEcdhEvents
import com.machankura.dedun.compose.nostr.groupdm.GroupEcdhPayload

/**
 * The admins' devices multiplying their shares by a point, together, until any `t` of them
 * give `d·P` (D3, D4, D5 and D11 of docs/group-threshold-ecdh.md).
 *
 * Nobody is asked anything: being an admin is the decision to read the group's mail (D7). A
 * device answers a round when its member is a current admin holding a share of the active
 * generation, it has the round's evidence and that evidence checks out. It answers once,
 * batches what it owes into one partials message per pass, seals it to the other current
 * admins' host keys, and combines as soon as it holds `t` proven partials. Everything is a
 * function of stored rows, so a device that dies mid-round picks up where it was.
 *
 * What a combined round gives is handed on through the database rather than a callback: a
 * seal's or a recipient's conversation key is kept in [GroupConversationKey] (D6), and a
 * wrap's opens its wrap at once, the seal going onto the ledger row for the receiver.
 */
object GroupEcdhManager {
    private val logger = Logger.withTag("GroupEcdhManager")

    /** Plaintext bytes per partials message: about thirty items, well inside a relay's event (Phase 0, check 6). */
    const val MAX_PAYLOAD_BYTES = 16_000

    /**
     * What one device knows of one room, enough to answer and combine. Null from [contextFor]
     * whenever this device must take no part: no completed key, no share of the active
     * generation, or its member is not a current admin (D4).
     */
    class RoomContext(
        val room: LocalChatRoom,
        val userPublicKey: HexKey,
        val key: DkgSession,
        val generation: Int,
        val myId: Int,
        private val secretShare: PrivateKey,
        val publicShares: List<PublicKey>,
        val threshold: Int,
        val tweak: NpubTweak,
        private val hostSecret: PrivateKey,
        val myHostKey: HexKey,
        /** Current admins holding a share of [generation], this device's member included: member to (id, host key). */
        val admins: Map<HexKey, Pair<Int, HexKey>>,
    ) {
        val roomId: HexKey get() = room.chatRoom.id

        internal fun partial(point: PublicKey): PublicKey = EcdhPartial.compute(secretShare, point)

        internal fun proof(point: PublicKey, partial: PublicKey): Dleq.Proof =
            Dleq.prove(secretShare, publicShares[myId], point, partial, Dleq.context(roomId, generation, myId), RandomInstance.bytes(32))

        internal fun seal(plaintext: String): AdminSeal.Sealed =
            AdminSeal.seal(plaintext, hostSecret, admins.filterKeys { it != userPublicKey }.values.map { it.second })

        internal fun open(sealed: AdminSeal.Sealed, senderHostKey: HexKey): String? =
            AdminSeal.open(sealed, hostSecret, myHostKey, senderHostKey)

        override fun toString(): String = "RoomContext(room=$roomId, generation=$generation, id=$myId, admins=${admins.size})"
    }

    suspend fun contextFor(database: DedunDatabase, room: LocalChatRoom, userPublicKey: HexKey, nostrPrivateKey: ByteArray): RoomContext? {
        val roomId = room.chatRoom.id
        val key = FrostSigningManager.completedKey(database, roomId) ?: return null
        val thresholdPublicKey = key.thresholdPublicKey ?: return null
        val shares = ShareGenerations.active(database, key)
        val secretShare = shares.secretShare ?: return null
        val publicShares = shares.publicShares ?: return null
        val myId = shares.signerIds[userPublicKey] ?: return null

        val roomAdmins = RoomAdmins.of(database, roomId)?.admins ?: return null
        if (userPublicKey !in roomAdmins) return null
        val admins = shares.roster
            .filter { it.memberPublicKey in roomAdmins && it.memberPublicKey in shares.signerIds }
            .associate { it.memberPublicKey to (it.participantId to it.hostPublicKey.lowercase()) }

        val hostSecret = ChillDkgRitualManager.deriveHostSecretKey(nostrPrivateKey)
        val myHostKey = ChillDkgRitualManager.deriveHostPublicKey(nostrPrivateKey).value.toHex()
        // A roster that names another host key for this member is one this device cannot
        // open slots under; it takes no part rather than sealing to keys that are not there.
        if (admins[userPublicKey]?.second != myHostKey) return null

        val tweak = tweakFor(database, room, thresholdPublicKey) ?: return null
        return RoomContext(
            room = room, userPublicKey = userPublicKey, key = key, generation = shares.generation, myId = myId,
            secretShare = secretShare, publicShares = publicShares, threshold = key.threshold, tweak = tweak,
            hostSecret = hostSecret, myHostKey = myHostKey, admins = admins,
        )
    }

    /** The tweak for whichever recorded path reaches the room's own id: the room id *is* the npub. */
    private suspend fun tweakFor(database: DedunDatabase, room: LocalChatRoom, thresholdPublicKey: HexKey): NpubTweak? {
        val roomId = room.chatRoom.id
        val candidates = listOfNotNull(
            GroupKeyStateManager.keyStateFor(database, roomId)?.pathIndices(),
            SharedKeyDerivation.parsePath(room.chatRoom.description),
            SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH,
        )
        val point = runCatching { PublicKey(thresholdPublicKey.hexToByteArray()) }.getOrNull() ?: return null
        return candidates.firstNotNullOfOrNull { path ->
            runCatching { NpubTweak.of(point, path) }.getOrNull()?.takeIf { it.npub == roomId }
        }
    }

    // ---- opening a round --------------------------------------------------------------------------------------

    /**
     * Opens the round for [point] at the active generation, or finds the one already open,
     * and sends this device's partial if it can. [evidence] travels only for a recipient;
     * a wrap or a seal is named by [ref], the wrap's id, which the ledger must already hold.
     */
    suspend fun open(
        database: DedunDatabase,
        context: RoomContext,
        point: HexKey,
        purpose: EcdhPurpose,
        ref: HexKey? = null,
        evidence: Event? = null,
    ): GroupEcdhRound {
        val round = ensureRound(database, context, point, purpose, ref, evidence)
        advance(database, context)
        return database.groupDmDao().getRound(round.id)!!
    }

    /** [open] without the advance: the round's row, made or found. For a caller already inside a pass. */
    suspend fun ensureRound(
        database: DedunDatabase,
        context: RoomContext,
        point: HexKey,
        purpose: EcdhPurpose,
        ref: HexKey? = null,
        evidence: Event? = null,
    ): GroupEcdhRound {
        val id = GroupEcdhPayload.roundId(context.roomId, context.generation, point)
        database.groupDmDao().insertRound(
            GroupEcdhRound(
                id = id, chatRoomId = context.roomId, generation = context.generation, point = point.lowercase(),
                purpose = purpose, ref = ref, evidenceJson = evidence?.toJson(),
            )
        )
        val round = database.groupDmDao().getRound(id)!!
        if (round.evidenceJson == null && evidence != null) {
            database.groupDmDao().upsertRound(round.copy(evidenceJson = evidence.toJson()))
        }
        return database.groupDmDao().getRound(id)!!
    }

    // ---- receiving ---------------------------------------------------------------------------------------------

    /**
     * A partials message or a failure from another admin's device. A device that is not a
     * current admin has no slot and reads nothing; one that is checks every proof, stores
     * what proved, names once what did not, and then owes and combines as it can.
     */
    suspend fun process(
        database: DedunDatabase,
        localChatRoom: LocalChatRoom,
        innerEvent: Event,
        userPublicKey: HexKey,
        nostrPrivateKey: ByteArray,
    ) {
        if (innerEvent.kind == GroupEcdhEvents.FAILURE) {
            // Every admin's device checks the same proofs itself; another's blame tells it
            // nothing it will not see, so it is filed with the inner event and not acted on.
            logger.i { "A round in ${localChatRoom.chatRoom.id} named participant ${GroupEcdhEvents.parseFailure(innerEvent.tags)?.second}" }
            return
        }
        if (innerEvent.kind != GroupEcdhEvents.PARTIALS) return
        val context = contextFor(database, localChatRoom, userPublicKey, nostrPrivateKey) ?: return
        val sender = innerEvent.pubKey
        if (sender == userPublicKey) return
        val (senderId, senderHost) = context.admins[sender] ?: return

        val sealed = AdminSeal.Sealed(innerEvent.content, AdminSeal.parseSlots(innerEvent.tags))
        val payload = context.open(sealed, senderHost)?.let(GroupEcdhPayload::decode) ?: return
        if (payload.from != senderId) return
        // Partials of two generations never combine, and this device answers only with its
        // active share. A sender behind or ahead restarts its rounds when it catches up.
        if (payload.generation != context.generation) return
        val publicShare = context.publicShares.getOrNull(senderId) ?: return

        val owedAgain = payload.items.mapNotNull { item -> receive(database, context, sender, senderId, publicShare, item) }
        if (owedAgain.isNotEmpty()) send(database, context, owedAgain.mapNotNull { round -> evidenceFor(database, context, round)?.let { round to it } })
        advance(database, context)
    }

    /**
     * Files one item. Returns the round when this device has already answered it and the sender
     * does not hold that answer ([GroupEcdhPayload.Item.have]): the partial goes out again.
     */
    private suspend fun receive(
        database: DedunDatabase,
        context: RoomContext,
        sender: HexKey,
        senderId: Int,
        publicShare: PublicKey,
        item: GroupEcdhPayload.Item,
    ): GroupEcdhRound? {
        val dao = database.groupDmDao()
        val roundId = GroupEcdhPayload.roundId(context.roomId, context.generation, item.point)
        dao.insertRound(
            GroupEcdhRound(
                id = roundId, chatRoomId = context.roomId, generation = context.generation, point = item.point,
                purpose = item.purpose, ref = item.ref, evidenceJson = item.evidence?.toJson(),
            )
        )
        val round = dao.getRound(roundId) ?: return null
        if (round.evidenceJson == null && item.evidence != null) dao.upsertRound(round.copy(evidenceJson = item.evidence.toJson()))

        val point = ThresholdEcdh.pointOf(item.point) ?: return null
        val verified = Dleq.verify(publicShare, point, item.partial, Dleq.context(context.roomId, context.generation, senderId), item.proof)
        val inserted = dao.insertPartial(
            GroupEcdhPartial(
                roundId = roundId, participantId = senderId, senderPublicKey = sender,
                partial = item.partial.value.toHex(), proof = item.proof.toHex(), verified = verified,
            )
        )
        if (!verified && inserted > 0) blame(database, context, roundId, senderId)
        val answered = dao.getPartials(roundId).any { it.participantId == context.myId }
        return round.takeIf { verified && inserted > 0 && answered && context.myId !in item.have }
    }

    /** A partial that did not prove: dropped, its sender named once per round (D3). */
    private suspend fun blame(database: DedunDatabase, context: RoomContext, roundId: HexKey, participantId: Int) {
        logger.w { "Participant $participantId's partial for round $roundId did not prove" }
        broadcast(database, context, GroupEcdhEvents.FAILURE, GroupEcdhEvents.failureTags(roundId, participantId), "A partial did not prove")
        database.groupDmDao().getPartials(roundId).firstOrNull { it.participantId == participantId }?.let {
            database.groupDmDao().upsertPartial(it.copy(blamedAt = Clock.System.now()))
        }
    }

    // ---- the sweep -----------------------------------------------------------------------------------------------

    /**
     * Everything a device can do for a room right now, in order: restart rounds a share
     * refresh overtook, send what it owes, combine what it can. Idempotent.
     */
    suspend fun advance(database: DedunDatabase, context: RoomContext) {
        restartOverTaken(database, context)
        // What a combined round makes possible -- a wrap's seal, a seal's message -- can open
        // another round, which this device can answer at once. A few turns settle it; each
        // only moves rows forward, so the bound is a guard, not a budget.
        repeat(4) {
            val stepped = GroupDmReceiver.step(database, context)
            val sent = flush(database, context)
            val combined = combine(database, context)
            if (!stepped && sent == 0 && combined.isEmpty()) return
        }
    }

    /** [advance], for every keyed room this profile is an admin of. */
    suspend fun sweep(database: DedunDatabase, userPublicKey: HexKey, nostrPrivateKey: ByteArray) {
        database.chatRoomDao().getChatRoomListByUserPublicKey(userPublicKey).forEach { room ->
            runCatching {
                contextFor(database, room, userPublicKey, nostrPrivateKey)?.let { advance(database, it) }
            }.onFailure { logger.e("Could not advance the rounds of ${room.chatRoom.id}", it) }
        }
    }

    private suspend fun restartOverTaken(database: DedunDatabase, context: RoomContext) {
        val dao = database.groupDmDao()
        dao.getOpenRounds(context.roomId).filter { it.generation != context.generation }.forEach { stale ->
            dao.upsertRound(stale.copy(state = GroupEcdhRoundState.FAILED))
            if (stale.generation < context.generation) {
                dao.insertRound(
                    stale.copy(
                        id = GroupEcdhPayload.roundId(context.roomId, context.generation, stale.point),
                        generation = context.generation, state = GroupEcdhRoundState.OPEN,
                        partialSentAt = null, openedAt = Clock.System.now(), combinedAt = null,
                    )
                )
            }
        }
    }

    /** The event that lets this device multiply by [round]'s point, if it holds one that checks (D5). */
    private suspend fun evidenceFor(database: DedunDatabase, context: RoomContext, round: GroupEcdhRound): Event? {
        val event = when (round.purpose) {
            EcdhPurpose.WRAP -> round.ref?.let { database.groupDmDao().getWrap(it) }?.wrapJson
            EcdhPurpose.SEAL -> round.ref?.let { database.groupDmDao().getWrap(it) }?.sealJson
            EcdhPurpose.RECIPIENT -> round.evidenceJson
        }?.let { runCatching { Event.fromJson(it) }.getOrNull() } ?: return null
        return event.takeIf { Evidence.allows(round.purpose, round.point, it, context.roomId) }
    }

    /**
     * Sends this device's partial for every open round it can answer and has not, batched
     * into as few partials messages as fit [MAX_PAYLOAD_BYTES]. Returns how many it sent.
     */
    suspend fun flush(database: DedunDatabase, context: RoomContext): Int {
        val dao = database.groupDmDao()
        val owed = dao.getOpenRounds(context.roomId)
            .filter { it.generation == context.generation && it.partialSentAt == null }
            .filter { round -> dao.getPartials(round.id).none { it.participantId == context.myId } }
            .mapNotNull { round -> evidenceFor(database, context, round)?.let { round to it } }
        if (owed.isEmpty()) return 0

        return send(database, context, owed)
    }

    /**
     * This device's partial for each of [rounds], batched into as few partials messages as fit
     * [MAX_PAYLOAD_BYTES], sealed to the other current admins, and recorded. Each item says
     * which partials this device already holds for its round. Returns how many it sent.
     */
    private suspend fun send(database: DedunDatabase, context: RoomContext, rounds: List<Pair<GroupEcdhRound, Event>>): Int {
        if (rounds.isEmpty()) return 0
        val dao = database.groupDmDao()
        val items = rounds.mapNotNull { (round, evidence) ->
            val point = ThresholdEcdh.pointOf(round.point) ?: return@mapNotNull null
            val partial = context.partial(point)
            val proof = context.proof(point, partial)
            val have = (dao.getPartials(round.id).filter { it.verified }.map { it.participantId } + context.myId).distinct().sorted()
            round to GroupEcdhPayload.Item(
                point = round.point, purpose = round.purpose,
                ref = round.ref.takeIf { round.purpose != EcdhPurpose.RECIPIENT },
                evidence = evidence.takeIf { round.purpose == EcdhPurpose.RECIPIENT },
                partial = partial, proof = proof, have = have,
            )
        }

        val batches = mutableListOf<MutableList<Pair<GroupEcdhRound, GroupEcdhPayload.Item>>>(mutableListOf())
        items.forEach { entry ->
            val candidate = batches.last() + entry
            val size = GroupEcdhPayload(context.generation, context.myId, candidate.map { it.second }).encode().length
            if (batches.last().isNotEmpty() && size > MAX_PAYLOAD_BYTES) batches += mutableListOf(entry) else batches.last() += entry
        }

        val now = Clock.System.now()
        batches.filter { it.isNotEmpty() }.forEach { batch ->
            val payload = GroupEcdhPayload(context.generation, context.myId, batch.map { it.second })
            if (context.admins.size > 1) {
                val sealed = context.seal(payload.encode())
                broadcast(database, context, GroupEcdhEvents.PARTIALS, GroupEcdhEvents.partialsTags(sealed), sealed.content)
            }
            batch.forEach { (round, item) ->
                dao.insertPartial(
                    GroupEcdhPartial(
                        roundId = round.id, participantId = context.myId, senderPublicKey = context.userPublicKey,
                        partial = item.partial.value.toHex(), proof = item.proof.toHex(), verified = true,
                    )
                )
                dao.upsertRound(dao.getRound(round.id)!!.copy(partialSentAt = now))
            }
        }
        return items.size
    }

    /**
     * Every open round of the active generation holding `t` proven partials, combined. A
     * seal's or a recipient's conversation key is kept; a wrap's opens its wrap, and is not.
     */
    suspend fun combine(database: DedunDatabase, context: RoomContext): List<GroupEcdhRound> {
        val dao = database.groupDmDao()
        val combined = mutableListOf<GroupEcdhRound>()
        dao.getOpenRounds(context.roomId).filter { it.generation == context.generation }.forEach { round ->
            val partials = dao.getPartials(round.id).filter { it.verified }
            if (partials.size < context.threshold) return@forEach
            val point = ThresholdEcdh.pointOf(round.point) ?: return@forEach
            val shared = ThresholdEcdh.combine(
                partials.associate { it.participantId to PublicKey(it.partial.hexToByteArray()) },
                context.tweak,
                point,
            )
            val conversationKey = ThresholdEcdh.conversationKey(shared)
            when (round.purpose) {
                EcdhPurpose.WRAP -> openWrap(database, round, conversationKey)
                EcdhPurpose.SEAL, EcdhPurpose.RECIPIENT -> dao.insertConversationKey(
                    GroupConversationKey(chatRoomId = context.roomId, peer = round.point, conversationKey = conversationKey.toHex(), roundId = round.id)
                )
            }
            val done = round.copy(state = GroupEcdhRoundState.COMBINED, combinedAt = Clock.System.now())
            dao.upsertRound(done)
            combined += done
        }
        return combined
    }

    /** `d·W` opens one wrap and is not kept (D6); the seal goes onto the ledger for the receiver. */
    private suspend fun openWrap(database: DedunDatabase, round: GroupEcdhRound, conversationKey: ByteArray) {
        val dao = database.groupDmDao()
        val ledger = round.ref?.let { dao.getWrap(it) } ?: return
        val wrap = runCatching { Event.fromJson(ledger.wrapJson) }.getOrNull()
        val seal = wrap?.let { GroupDmWrap.open(it, conversationKey) }
        dao.upsertWrap(
            if (seal == null) ledger.copy(state = GroupDmWrapState.UNREADABLE)
            else ledger.copy(sealJson = seal.toJson())
        )
    }

    // ---- transport -------------------------------------------------------------------------------------------------

    private suspend fun broadcast(database: DedunDatabase, context: RoomContext, kind: Int, tags: Array<Array<String>>, content: String) {
        val author = context.userPublicKey
        var createdAt = Clock.System.now().epochSeconds
        var id = EventHasher.hashId(pubKey = author, createdAt = createdAt, kind = kind, tags = tags, content = content)
        var attempts = 0
        while (attempts < 16 && database.marmotInnerEventDao().getById(id) != null) {
            createdAt += 1
            id = EventHasher.hashId(pubKey = author, createdAt = createdAt, kind = kind, tags = tags, content = content)
            attempts += 1
        }
        database.marmotInnerEventDao().upsert(
            MarmotInnerEvent(
                id = id, publicKey = author, kind = kind, createdAt = Instant.fromEpochSeconds(createdAt),
                tags = tags, content = content, chatRoomId = context.roomId,
            )
        )
    }
}
