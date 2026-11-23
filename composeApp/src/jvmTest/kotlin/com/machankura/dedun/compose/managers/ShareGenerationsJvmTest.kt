package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.Nip01Crypto
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.crypto.frost.Frost
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.DkgParticipantMessage
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.KeyShareGeneration
import com.machankura.dedun.compose.database.model.KeyShareMember
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.database.model.types.ShareGenerationState
import com.machankura.dedun.compose.database.model.types.ShareStanding
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.nostr.frost.tags.FrostGenerationTag

/**
 * Signing once a key's shares can change (docs/frost-share-refresh.md, Phase 6).
 *
 * A refresh gives every member a new share of the same key, on a new
 * polynomial. Nothing in the app makes one yet -- that is Phases 1-3 and 7 --
 * so the refresh here is done by hand, the way the protocol will do it: a
 * polynomial with a zero constant term, evaluated at each member's point and
 * added to their share. If the app's signing reads the generation it should,
 * the new shares sign under the unchanged key, the room's id does not move,
 * and a member holding only the old share cannot take part.
 *
 * Three members, 2-of-3, in a Marmot admin room, as [SignedGroupKeyStateTest]
 * sets them up: one database each, and nothing shared but what is ferried.
 */
class ShareGenerationsJvmTest {
    private val participants = 3
    private val threshold = 2

    private val keyMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(
            ByteVector32("5ea1ed0000000000000000000000000000000000000000000000000000000ab1")
        ),
        nParticipants = participants,
        threshold = threshold
    )

    private val thresholdPublicKey = keyMaterial.thresholdPublicKey.value.toHex()
    private val adminRoomId = SharedKeyDerivation.marmotGroupId(thresholdPublicKey)
    private val ceremonyId = "ceremony".padEnd(64, '0')

    /** Chosen so the bytewise sort of the host keys reads in list order: member i is participant i. */
    private val members = listOf("a", "b", "c").map { it.repeat(64) }
    private val hostKeys = listOf("2a", "2b", "2c").map { it.padEnd(66, '0') }

    // ---- The refresh, by hand -------------------------------------------------

    /**
     * delta(x) = a·x: degree t-1 with no constant term, so it moves every share
     * and leaves the secret where it is. Participant i sits at x = i + 1, the
     * frost module's convention.
     */
    private val deltaCoefficient = PrivateKey(
        ByteVector32("0de17a0000000000000000000000000000000000000000000000000000000c0e")
    )

    private fun scalar(value: Int): PrivateKey =
        PrivateKey(ByteVector32(ByteArray(32).also { it[31] = value.toByte() }))

    private fun delta(participant: Int): PrivateKey = deltaCoefficient * scalar(participant + 1)

    private val refreshedSecretShares: List<PrivateKey> =
        keyMaterial.secretShares.mapIndexed { i, share -> share + delta(i) }

    private val refreshedPublicShares: List<PublicKey> =
        keyMaterial.publicShares.mapIndexed { i, share -> share + delta(i).publicKey() }

    // ---- Devices ---------------------------------------------------------------

    private val devices = mutableListOf<Device>()

    @AfterTest
    fun closeDatabases() = devices.forEach { it.db.close() }

    private inner class Device(val publicKey: HexKey, val db: DedunDatabase, val room: LocalChatRoom) {
        val received = mutableSetOf<String>()
        suspend fun session(id: String) = db.frostSigningSessionDao().getSessionById(id)
        suspend fun key() = db.dkgSessionDao().getSessionById(ceremonyId)!!
    }

    private suspend fun device(index: Int): Device {
        val publicKey = members[index]
        val db = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

        members.forEach { member ->
            val nostrEventId = "e$member".take(64)
            db.nostrEventDao().upsert(
                NostrEvent(id = nostrEventId, pubKey = member, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128))
            )
            db.profileDao().upsert(Profile(publicKey = member, nostrEventId = nostrEventId))
        }

        val chatRoom = ChatRoom(
            id = adminRoomId,
            userPublicKey = publicKey,
            subject = "Ekklesia",
            description = SharedKeyDerivation.describe("Admins of the group."),
            mlsGroupState = "00"
        )
        db.chatRoomDao().upsert(chatRoom)

        db.dkgSessionDao().upsert(
            DkgSession(
                id = ceremonyId,
                chatRoomId = adminRoomId,
                coordinatorPublicKey = members.first(),
                userPublicKey = publicKey,
                threshold = threshold,
                participantCount = participants,
                stage = DkgRitualStage.COMPLETE,
                hostPublicKey = hostKeys[index],
                round1Random = "1".repeat(64),
                round2AuxRandom = "2".repeat(64),
                thresholdPublicKey = thresholdPublicKey,
                secretShare = keyMaterial.secretShares[index].value.toHex(),
                publicShares = keyMaterial.publicShares.joinToString(",") { it.value.toHex() }
            )
        )
        members.forEachIndexed { i, member ->
            db.dkgSessionDao().upsert(
                DkgParticipantMessage(sessionId = ceremonyId, participantPublicKey = member, kind = DkgRitualEvents.HOST_KEY, payload = hostKeys[i])
            )
        }

        return Device(publicKey, db, LocalChatRoom(chatRoom = chatRoom)).also { devices += it }
    }

    /**
     * Files generation 1 on [device] as a finished refresh would: the device's
     * new share (none if it was [withheld]), the new public table, and every
     * member's standing.
     */
    private suspend fun refresh(
        device: Device,
        withheld: Set<Int> = emptySet(),
        state: ShareGenerationState = ShareGenerationState.ACTIVE,
    ) {
        val index = members.indexOf(device.publicKey)
        device.db.keyShareDao().upsert(
            KeyShareGeneration(
                dkgSessionId = ceremonyId,
                generation = 1,
                secretShare = refreshedSecretShares[index].value.toHex().takeIf { index !in withheld },
                publicShares = refreshedPublicShares.joinToString(",") { it.value.toHex() },
                state = state,
            )
        )
        device.db.keyShareDao().upsert(
            members.mapIndexed { i, member ->
                KeyShareMember(
                    dkgSessionId = ceremonyId,
                    generation = 1,
                    participantId = i,
                    memberPublicKey = member,
                    hostPublicKey = hostKeys[i],
                    standing = if (i in withheld) ShareStanding.STALE else ShareStanding.LIVE,
                )
            }
        )
    }

    // ---- The wire, as SignedGroupKeyStateTest ferries it ------------------------

    private suspend fun outbox(device: Device): List<MarmotInnerEvent> =
        device.db.marmotInnerEventDao()
            .getByChatRoomAndKinds(chatRoomId = adminRoomId, kinds = FrostSigningEvents.ALL.toList())
            .filter { it.publicKey == device.publicKey }
            .sortedWith(compareBy({ it.createdAt }, { it.kind }))

    private fun MarmotInnerEvent.toEvent() =
        Event(id = id, pubKey = publicKey, createdAt = createdAt.epochSeconds, kind = kind, tags = tags, content = content, sig = "")

    private suspend fun pump(vararg between: Device) {
        var moved = true
        while (moved) {
            moved = false
            for (from in between) for (to in between) {
                if (from === to) continue
                outbox(from).forEach { queued ->
                    if (!to.received.add(queued.id)) return@forEach
                    moved = true
                    to.db.marmotInnerEventDao().upsert(queued.copy(chatRoomId = adminRoomId))
                    FrostSigningManager.processSigningPayload(to.db, to.room, queued.toEvent(), to.publicKey)
                }
            }
        }
    }

    private suspend fun propose(by: Device) = FrostSigningManager.proposeSigning(
        database = by.db,
        localChatRoom = by.room,
        userPublicKey = by.publicKey,
        kind = 1,
        tags = arrayOf(),
        content = "Said with the new shares"
    )

    private suspend fun proposalOf(device: Device, sessionId: String) = outbox(device).single {
        it.kind == FrostSigningEvents.PROPOSAL && FrostSigningEvents.parseSessionId(it.tags) == sessionId
    }

    // ---- Generation 0 ----------------------------------------------------------

    /**
     * Generation 0 has no row: it is read where the ceremony wrote it, with ids
     * derived from the ceremony's host keys exactly as signing always derived
     * them. If this ever disagrees with the ceremony, every signer lands in the
     * wrong slot -- which is why it is derived and not copied.
     */
    @Test
    fun `generation 0 is the ceremony's, read where the ceremony wrote it`() = runBlocking<Unit> {
        val alice = device(0)
        val shares = ShareGenerations.active(alice.db, alice.key())

        assertEquals(0, shares.generation)
        assertEquals(keyMaterial.secretShares[0], shares.secretShare)
        assertEquals(keyMaterial.publicShares, shares.publicShares)
        assertEquals(members.withIndex().associate { (i, m) -> m to i }, shares.signerIds)
        assertEquals(shares.signerIds, shares.participantIds)
        assertTrue(ShareGenerations.canSign(alice.db, alice.key()))
    }

    @Test
    fun `a proposal at generation 0 carries no generation tag`() = runBlocking<Unit> {
        val alice = device(0)
        val session = propose(alice)

        assertEquals(0, session.generation)
        val proposal = proposalOf(alice, session.id)
        assertNull(proposal.tags.firstOrNull { it[0] == FrostGenerationTag.TAG_NAME }, "an older build must read this as it always has")
        assertEquals(0, FrostSigningEvents.parseGeneration(proposal.tags))
    }

    // ---- Generation 1 ----------------------------------------------------------

    @Test
    fun `a pending generation signs nothing`() = runBlocking<Unit> {
        val alice = device(0)
        refresh(alice, state = ShareGenerationState.PENDING)

        assertNull(ShareGenerations.at(alice.db, alice.key(), 1))
        assertEquals(0, ShareGenerations.active(alice.db, alice.key()).generation)
    }

    /**
     * The point of the phase: after a refresh the group signs with the new
     * shares, and the signature verifies under the room's id, which did not
     * move. If signing still read the ceremony's share, Alice's and Bob's
     * partial signatures would be checked against public shares they do not
     * match, and nothing would aggregate.
     */
    @Test
    fun `after a refresh the group signs with the new shares, as the same room`() = runBlocking<Unit> {
        val alice = device(0)
        val bob = device(1)
        refresh(alice)
        refresh(bob)

        val session = propose(alice)
        assertEquals(1, session.generation)
        assertEquals(1, FrostSigningEvents.parseGeneration(proposalOf(alice, session.id).tags))

        pump(alice, bob)
        assertEquals(1, bob.session(session.id)?.generation)
        FrostSigningManager.approve(bob.db, bob.room, session.id)
        pump(alice, bob)

        listOf(alice, bob).forEach { device ->
            assertEquals(FrostSigningStage.COMPLETE, device.session(session.id)?.stage)
            val item = device.db.frostSigningSessionDao().getItems(session.id).single()
            assertTrue(
                Nip01Crypto.verify(
                    signature = assertNotNull(item.signature).hexToByteArray(),
                    hash = item.eventId.hexToByteArray(),
                    pubKey = adminRoomId.hexToByteArray()
                ),
                "signed with the new shares, the signature is still the room's"
            )
        }
    }

    /**
     * A device that never heard of the refresh holds only the ceremony's share.
     * It cannot help, and it says so: a refusal with a reason, which the group
     * counts, rather than silence or a partial signature that cannot aggregate.
     */
    @Test
    fun `a device without the named generation declines out loud`() = runBlocking<Unit> {
        val alice = device(0)
        val bob = device(1)
        refresh(alice)
        // Bob's device missed the refresh: generation 0 only.

        val session = propose(alice)
        pump(alice, bob)

        val onBob = assertNotNull(bob.session(session.id))
        assertNotNull(onBob.signDeclinedAt, "Bob's device must refuse rather than wait for an approval it cannot act on")
        val decline = outbox(bob).single { it.kind == FrostSigningEvents.DECLINE }
        assertEquals(FrostSigningManager.GENERATION_REFUSAL, decline.content)
        assertNotNull(
            alice.db.frostSigningSessionDao().getMessage(session.id, FrostSigningEvents.DECLINE, bob.publicKey),
            "and Alice's device counts it"
        )
    }

    /**
     * A member withheld from the refresh is stale at generation 1: they keep an
     * id, but they are not a signer, and their own device knows it.
     */
    @Test
    fun `a withheld member is not a signer at the new generation`() = runBlocking<Unit> {
        val alice = device(0)
        val carol = device(2)
        refresh(alice, withheld = setOf(2))
        refresh(carol, withheld = setOf(2))

        val onAlice = ShareGenerations.active(alice.db, alice.key())
        assertEquals(setOf(members[0], members[1]), onAlice.signerIds.keys)
        assertEquals(2, onAlice.participantIds[members[2]])

        val onCarol = ShareGenerations.active(carol.db, carol.key())
        assertEquals(1, onCarol.generation)
        assertNull(onCarol.secretShare)
        assertEquals(false, ShareGenerations.canSign(carol.db, carol.key()))
        assertNull(FrostSigningManager.completedKey(carol.db, adminRoomId), "Carol's device must offer nothing it would fail at")

        val session = propose(alice)
        pump(alice, carol)
        assertEquals(FrostSigningManager.GENERATION_REFUSAL, outbox(carol).single { it.kind == FrostSigningEvents.DECLINE }.content)
    }

    /** The refresh by hand is a refresh: the same key, different shares. */
    @Test
    fun `the hand-made refresh keeps the key and moves every share`() {
        assertTrue(KeyMaterial(keyMaterial.thresholdPublicKey, refreshedSecretShares, refreshedPublicShares, threshold).isValid())
        refreshedSecretShares.forEachIndexed { i, share ->
            assertNotEquals(keyMaterial.secretShares[i], share)
            assertEquals(refreshedPublicShares[i], share.publicKey())
        }
    }
}
