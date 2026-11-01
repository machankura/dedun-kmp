package com.machankura.dedun.compose.database.dao

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.DkgParticipantMessage
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.FrostSignerMessage
import com.machankura.dedun.compose.database.model.FrostSigningItem
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The signing counterpart to [DkgSessionDaoJvmTest], and it differs from the DKG in one way
 * that shapes every query here: "unlike a DKG a group signs repeatedly, so there is no single
 * current one to observe". Sessions accumulate, which makes room scoping and ordering
 * load-bearing rather than incidental.
 *
 * The duplicate-suppression property is the same and matters for the same reason. The composite
 * key (sessionId, signerPublicKey, kind) is what makes a redelivered nonce or partial signature
 * replace its predecessor instead of adding a row, and `countMessagesByKind` is what decides
 * that enough signers have answered. A second row for one signer would let a session cross its
 * threshold while short a real participant.
 */
class FrostSigningSessionDaoJvmTest {

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )

    @AfterTest
    fun closeDb() = db.close()

    private val user = KeyPair().pubKey.toHexKey()
    private val alice = KeyPair().pubKey.toHexKey()
    private val bob = KeyPair().pubKey.toHexKey()
    private val roomOne = "11".repeat(32)
    private val roomTwo = "22".repeat(32)

    private val nonceKind = 1
    private val partialSignatureKind = 2

    private suspend fun seedRooms() {
        val nostrEventId = "c".repeat(64)
        db.nostrEventDao().upsert(
            NostrEvent(
                id = nostrEventId,
                pubKey = user,
                kind = 0,
                tags = emptyArray(),
                content = "{}",
                sig = "0".repeat(128),
            )
        )
        db.profileDao().upsert(Profile(publicKey = user, userName = "user", nostrEventId = nostrEventId))
        listOf(roomOne, roomTwo).forEach { id ->
            db.chatRoomDao().upsert(
                ChatRoom(
                    id = id,
                    userPublicKey = user,
                    subject = null,
                    description = null,
                    mlsGroupState = null,
                )
            )
        }
    }

    private suspend fun session(
        id: String,
        chatRoomId: String = roomOne,
        createdAt: Instant = Instant.fromEpochSeconds(1_000),
        stage: FrostSigningStage = FrostSigningStage.COLLECTING_NONCES,
        signature: String? = null,
        events: Int = 1,
    ): FrostSigningSession = FrostSigningSession(
        id = id,
        chatRoomId = chatRoomId,
        coordinatorPublicKey = user,
        userPublicKey = user,
        dkgSessionId = "dkg-1",
        threshold = 2,
        participantCount = 3,
        signerId = 1,
        stage = stage,
        createdAt = createdAt,
    ).also {
        db.frostSigningSessionDao().upsert(it)
        db.frostSigningSessionDao().upsertItems(
            (0 until events).map { index -> item(id, index, signature) }
        )
    }

    /**
     * One event of a session's batch. Distinct ids and seeds per index, because
     * that is what the manager writes and what every ordering assertion here
     * would otherwise be blind to.
     */
    private fun item(
        sessionId: String,
        itemIndex: Int = 0,
        signature: String? = null,
        aggregatedNonce: String? = null,
    ) = FrostSigningItem(
        sessionId = sessionId,
        itemIndex = itemIndex,
        unsignedEventJson = """{"index":$itemIndex}""",
        eventId = "f$itemIndex".repeat(32),
        nonceRandom = "a$itemIndex".repeat(32),
        aggregatedNonce = aggregatedNonce,
        signature = signature,
    )

    private suspend fun message(
        sessionId: String,
        signer: String,
        kind: Int = nonceKind,
        payload: String = "aa".repeat(32),
        createdAt: Instant = Instant.fromEpochSeconds(1_000),
    ) = db.frostSigningSessionDao().upsert(
        FrostSignerMessage(
            sessionId = sessionId,
            signerPublicKey = signer,
            kind = kind,
            payload = payload,
            createdAt = createdAt,
        )
    )

    @Test
    fun `a signing session reads back by its id`() = runBlocking {
        seedRooms()
        session("s1", stage = FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES)

        val found = assertNotNull(db.frostSigningSessionDao().getSessionById("s1"))

        assertEquals(FrostSigningStage.COLLECTING_PARTIAL_SIGNATURES, found.stage)
        assertNull(db.frostSigningSessionDao().getSessionById("nope"))
    }

    /**
     * Sessions accumulate rather than replacing each other, so a room keeps a history and the
     * newest is the one a resume cares about.
     */
    @Test
    fun `a rooms signing sessions accumulate newest first`() = runBlocking {
        seedRooms()
        session("first", createdAt = Instant.fromEpochSeconds(1_000))
        session("second", createdAt = Instant.fromEpochSeconds(2_000))
        session("third", createdAt = Instant.fromEpochSeconds(3_000))

        assertEquals(
            listOf("third", "second", "first"),
            db.frostSigningSessionDao().getSessionsForChatRoom(roomOne).map { it.id },
        )
        assertEquals("third", db.frostSigningSessionDao().getLatestSessionForChatRoom(roomOne)?.id)
    }

    /**
     * What the proposal list reads. A room can have two sessions open at once, so the list has
     * to carry every one of them with what it signs -- the events are what names a proposal,
     * and a session row on its own says only how the signing is going.
     */
    @Test
    fun `a rooms proposals arrive with their events and their proposer`() = runBlocking {
        seedRooms()
        session("first", createdAt = Instant.fromEpochSeconds(1_000), events = 3)
        session("second", createdAt = Instant.fromEpochSeconds(2_000), events = 1)

        val proposals = db.frostSigningSessionDao().observeSessionsForChatRoom(roomOne).first()

        assertEquals(listOf("second", "first"), proposals.map { it.session.id })

        // Room does not order a relation, so the batch order is the model's doing.
        // It is protocol rather than presentation: two devices reading a batch in
        // different orders aggregate against different messages.
        val first = assertNotNull(proposals.firstOrNull { it.session.id == "first" })
        assertEquals(listOf(0, 1, 2), first.items.map { it.itemIndex })

        // Named, not keyed. A proposal is somebody asking the group for something,
        // and a row that cannot say who is asking is worse than an ugly one.
        assertEquals("user", first.proposer?.userName)
    }

    /**
     * Signing happens in the admin room, and a device can be in more than one. A session from
     * another room appearing here would have a signer answering a request its group never made.
     */
    @Test
    fun `sessions are scoped to their own room`() = runBlocking {
        seedRooms()
        session("mine", chatRoomId = roomOne)
        session("theirs", chatRoomId = roomTwo, createdAt = Instant.fromEpochSeconds(9_000))

        assertEquals(listOf("mine"), db.frostSigningSessionDao().getSessionsForChatRoom(roomOne).map { it.id })
        assertEquals("mine", db.frostSigningSessionDao().getLatestSessionForChatRoom(roomOne)?.id)
        assertEquals(emptyList(), db.frostSigningSessionDao().getSessionsForChatRoom("33".repeat(32)))
    }

    /**
     * The threshold gate. A redelivered nonce must replace the signer's earlier one rather than
     * add a row, or the count crosses the threshold with fewer signers than the session
     * requires -- and the aggregation proceeds on a set that was never assembled.
     */
    @Test
    fun `a resent nonce replaces the signers earlier one`() = runBlocking {
        seedRooms()
        session("s1")
        message("s1", alice, payload = "aa".repeat(32))

        message("s1", alice, payload = "bb".repeat(32))

        assertEquals(1, db.frostSigningSessionDao().countMessagesByKind("s1", nonceKind))
        assertEquals(
            "bb".repeat(32),
            db.frostSigningSessionDao().getMessage("s1", nonceKind, alice)?.payload,
        )
    }

    /**
     * A signer contributes to both rounds of a session -- a nonce and then a partial signature
     * -- and the kind in the composite key is what keeps the second from overwriting the first.
     */
    @Test
    fun `a signer holds a nonce and a partial signature at once`() = runBlocking {
        seedRooms()
        session("s1")
        message("s1", alice, kind = nonceKind, payload = "aa".repeat(32))
        message("s1", alice, kind = partialSignatureKind, payload = "cc".repeat(32))

        assertEquals(1, db.frostSigningSessionDao().countMessagesByKind("s1", nonceKind))
        assertEquals(1, db.frostSigningSessionDao().countMessagesByKind("s1", partialSignatureKind))
        assertEquals(
            "aa".repeat(32),
            db.frostSigningSessionDao().getMessage("s1", nonceKind, alice)?.payload,
            "the partial signature overwrote the nonce",
        )
    }

    @Test
    fun `messages are counted per session and per round`() = runBlocking {
        seedRooms()
        session("s1")
        session("s2")
        message("s1", alice, kind = nonceKind)
        message("s1", bob, kind = nonceKind)
        message("s2", alice, kind = nonceKind)

        assertEquals(2, db.frostSigningSessionDao().countMessagesByKind("s1", nonceKind))
        assertEquals(1, db.frostSigningSessionDao().countMessagesByKind("s2", nonceKind))
        assertEquals(0, db.frostSigningSessionDao().countMessagesByKind("s1", partialSignatureKind))
        assertNull(db.frostSigningSessionDao().getMessage("s1", nonceKind, user))
    }

    /**
     * Worth recording because it is the one place these two DAOs disagree: FROST orders a
     * round's messages by `createdAt`, where the DKG orders the same query by participant
     * public key. Arrival order is per-device, so this ordering is not canonical across the
     * group the way the DKG's is. Pinned as it stands rather than assumed to be either
     * deliberate or a slip -- a caller that needs a canonical signer order has to impose one.
     */
    @Test
    fun `a rounds messages are ordered by arrival, unlike the dkg`() = runBlocking {
        seedRooms()
        session("s1")
        val byKey = listOf(alice, bob).sorted()
        message("s1", byKey[1], createdAt = Instant.fromEpochSeconds(1_000))
        message("s1", byKey[0], createdAt = Instant.fromEpochSeconds(2_000))

        val found = db.frostSigningSessionDao().getMessagesByKind("s1", nonceKind).map { it.signerPublicKey }

        assertEquals(listOf(byKey[1], byKey[0]), found, "FROST returns a round in arrival order")
    }

    /** A finished session keeps its signature, which is what a resume reads to avoid re-signing. */
    @Test
    fun `a completed session keeps its signature`() = runBlocking {
        seedRooms()
        session("s1", stage = FrostSigningStage.COMPLETE, signature = "ab".repeat(32))

        val found = assertNotNull(db.frostSigningSessionDao().getSessionById("s1"))

        assertEquals(FrostSigningStage.COMPLETE, found.stage)
        assertEquals("ab".repeat(32), db.frostSigningSessionDao().getItems("s1").single().signature)
    }

    /**
     * A batch's items come back in the order the proposal fixed, whatever order
     * they went in.
     *
     * Not a presentation detail. Nonces and partial signatures are joined
     * positionally against this list, so a device reading a batch in a different
     * order aggregates item 2's partial signature against item 0's message and
     * produces a signature nobody can verify.
     */
    @Test
    fun `a batch's items read back in index order`() = runBlocking {
        seedRooms()
        session("s1")

        db.frostSigningSessionDao().upsertItems(
            listOf(item("s1", itemIndex = 3), item("s1", itemIndex = 1), item("s1", itemIndex = 2))
        )

        val items = db.frostSigningSessionDao().getItems("s1")

        assertEquals(listOf(0, 1, 2, 3), items.map { it.itemIndex })
        assertEquals(4, db.frostSigningSessionDao().countItems("s1"))
    }

    /**
     * A redelivered item overwrites rather than accumulates, which is what the
     * composite key buys — the same property the signer messages rely on, and for
     * the same reason: a second row would make the batch the wrong length, and
     * the length is what every payload is checked against.
     */
    @Test
    fun `a second write of one item replaces it`() = runBlocking {
        seedRooms()
        session("s1")

        db.frostSigningSessionDao().upsert(item("s1", signature = "cd".repeat(32)))

        val items = db.frostSigningSessionDao().getItems("s1")

        assertEquals(1, items.size)
        assertEquals("cd".repeat(32), items.single().signature)
    }

    /** Counted rather than flagged, so "the group has finished" cannot disagree with the rows. */
    @Test
    fun `signed items are counted apart from the rest`() = runBlocking {
        seedRooms()
        session("s1", events = 3)

        assertEquals(3, db.frostSigningSessionDao().countItems("s1"))
        assertEquals(0, db.frostSigningSessionDao().countSignedItems("s1"))

        db.frostSigningSessionDao().upsertItems(
            listOf(item("s1", 0, signature = "ab".repeat(32)), item("s1", 1, signature = "cd".repeat(32)))
        )

        assertEquals(2, db.frostSigningSessionDao().countSignedItems("s1"))
    }

    /** Items belong to their session and go with it, like the signer messages do. */
    @Test
    fun `deleting a room takes its sessions' items with it`() = runBlocking {
        seedRooms()
        session("s1", chatRoomId = roomOne, events = 2)
        session("s2", chatRoomId = roomTwo, events = 2)

        db.chatRoomDao().delete(assertNotNull(db.chatRoomDao().findChatRoomById(roomOne)).chatRoom)

        assertEquals(emptyList(), db.frostSigningSessionDao().getItems("s1"))
        assertEquals(2, db.frostSigningSessionDao().getItems("s2").size)
    }

    /** The single-item read the manager uses to pick one event out of a batch. */
    @Test
    fun `an item can be read by its index`() = runBlocking {
        seedRooms()
        session("s1", events = 3)

        assertEquals("f2".repeat(32), db.frostSigningSessionDao().getItem("s1", 2)?.eventId)
        assertNull(db.frostSigningSessionDao().getItem("s1", 3))
    }

    // --- the progress tallies -----------------------------------------------------

    private val carol = KeyPair().pubKey.toHexKey()
    private val hostKeyKind = 9

    /** A ceremony, and who holds a share of the key it made. */
    private suspend fun ceremony(holders: List<String>, id: String = "dkg-1") {
        db.dkgSessionDao().upsert(
            DkgSession(
                id = id,
                chatRoomId = roomOne,
                coordinatorPublicKey = user,
                userPublicKey = user,
                threshold = 2,
                participantCount = 3,
                hostPublicKey = user,
                round1Random = "aa".repeat(32),
                round2AuxRandom = "bb".repeat(32),
            )
        )

        holders.forEach { holder ->
            db.dkgSessionDao().upsert(
                DkgParticipantMessage(
                    sessionId = id,
                    participantPublicKey = holder,
                    kind = hostKeyKind,
                    payload = "cc".repeat(32),
                )
            )
        }
    }

    private suspend fun tallies(chatRoomId: String = roomOne) =
        db.frostSigningSessionDao().observeSignerTalliesForChatRoom(
            chatRoomId = chatRoomId,
            kinds = listOf(nonceKind, partialSignatureKind),
            hostKeyKind = hostKeyKind,
        ).first()

    @Test
    fun `a tally counts the members who answered, per session and per kind`() = runBlocking {
        seedRooms()
        ceremony(holders = listOf(user, alice, bob))
        session("one")
        session("two")

        message("one", alice, nonceKind)
        message("one", bob, nonceKind)
        message("one", alice, partialSignatureKind)
        message("two", alice, nonceKind)

        val byKey = tallies().associate { (it.sessionId to it.kind) to it.members }

        assertEquals(2, byKey[("one" to nonceKind)])
        assertEquals(1, byKey[("one" to partialSignatureKind)])
        assertEquals(1, byKey[("two" to nonceKind)])
        assertNull(byKey[("two" to partialSignatureKind)], "a kind nobody sent has no row")
    }

    /**
     * The restriction the figure rests on.
     *
     * `FrostSigningManager` records a nonce or a partial signature without
     * checking who sent it -- unlike a refusal, which it filters -- and
     * `orderedNonces` then drops the ones from members holding no share when it
     * aggregates. A tally that counted those would have the list read ahead of
     * the coordinator: a row saying the group has the two offers it needs while
     * the session sits still, which is the one direction this figure must never
     * be wrong in.
     */
    @Test
    fun `a nonce from somebody holding no share is not counted`() = runBlocking {
        seedRooms()
        ceremony(holders = listOf(alice, bob))
        session("one")

        message("one", alice, nonceKind)
        message("one", carol, nonceKind)

        assertEquals(
            1,
            tallies().single { it.kind == nonceKind }.members,
            "a stranger's nonce was counted towards the threshold"
        )
    }

    /** A session nobody has answered has no row at all, which a reader takes as zero. */
    @Test
    fun `a session with no messages has no tally`() = runBlocking {
        seedRooms()
        ceremony(holders = listOf(alice, bob))
        session("quiet")

        assertEquals(emptyList(), tallies())
    }

    /**
     * The composite key already makes a redelivered message replace its
     * predecessor, so a member cannot be counted twice by sending twice. The
     * count is of members either way, which is what the threshold is counted in.
     */
    @Test
    fun `a member who answers twice is still one member`() = runBlocking {
        seedRooms()
        ceremony(holders = listOf(alice, bob))
        session("one")

        message("one", alice, nonceKind, payload = "aa".repeat(32))
        message("one", alice, nonceKind, payload = "bb".repeat(32))

        assertEquals(1, tallies().single().members)
    }

    @Test
    fun `a tally is scoped to its room`() = runBlocking {
        seedRooms()
        ceremony(holders = listOf(alice, bob))
        session("here", chatRoomId = roomOne)
        session("elsewhere", chatRoomId = roomTwo)

        message("here", alice, nonceKind)
        message("elsewhere", alice, nonceKind)

        assertEquals(listOf("here"), tallies(roomOne).map { it.sessionId })
        assertEquals(listOf("elsewhere"), tallies(roomTwo).map { it.sessionId })
    }

    /** Kinds the caller did not ask for stay out, so a refusal is not read as an offer. */
    @Test
    fun `a kind outside the asked-for set is not tallied`() = runBlocking {
        seedRooms()
        ceremony(holders = listOf(alice, bob))
        session("one")

        message("one", alice, kind = 99)

        assertEquals(emptyList(), tallies())
    }
}
