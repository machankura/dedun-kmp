package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.crypto.frost.Frost
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.DkgParticipantMessage
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.FrostSigningItem
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.repository.DatabaseFrostSigningRepository
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents

/**
 * What one member's refusal does to a signing session, against a real
 * database and the real manager.
 *
 * A t-of-n key needs t willing members, not n, so a refusal used to do far
 * more than it should: one member pressing "Don't sign" ended the session for
 * everybody, in a group whose whole reason for running a ceremony was that no
 * one member is needed. Now a refusal takes one member out of the n, and the
 * session is abandoned only once fewer than t are left -- in the 2-of-3 group
 * here, on the second refusal and not the first.
 *
 * Every device counts for itself, from the refusals it has received, and the
 * count is checked from both sides: refusals arriving from other members, and
 * this device's own, which is recorded as a decision and published the way an
 * approval publishes a nonce.
 */
class FrostSigningDeclineJvmTest {
    private val participants = 3
    private val threshold = 2

    /** Stands in for a completed ceremony; the test is about refusing, not the DKG. */
    private val keyMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(
            ByteVector32("1c0ffee0000000000000000000000000000000000000000000000000000000a1")
        ),
        nParticipants = participants,
        threshold = threshold
    )

    private val thresholdPublicKey = keyMaterial.thresholdPublicKey.value.toHex()

    /** The admin room: the group's key walked to the admin path, which is its id. */
    private val roomId = SharedKeyDerivation.marmotGroupId(thresholdPublicKey)

    private val ceremonyId = "ceremony".padEnd(64, '0')
    private val members = listOf("a", "b", "c").map { it.repeat(64) }
    private val hostKeys = listOf("2a", "2b", "2c").map { it.padEnd(66, '0') }

    private val alice = members[0]
    private val bob = members[1]
    private val carol = members[2]

    /** A member of the room who holds no share: never in the ceremony at all. */
    private val stranger = "d".repeat(64)

    private lateinit var db: DedunDatabase
    private lateinit var room: LocalChatRoom

    @AfterTest
    fun closeDatabase() {
        if (::db.isInitialized) db.close()
    }

    /**
     * One member's device: a room it can sign in, and their share to sign with.
     *
     * [owner] is whose device this is. The share and host key are theirs, and
     * the room is theirs; everything else -- the ceremony, the participant
     * order the signer ids come from -- is the same on every device.
     */
    private suspend fun openDevice(owner: HexKey = alice) {
        db = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

        val index = members.indexOf(owner)

        // Profile hangs off a nostr event, and a room off a profile. Neither is
        // anything this test is about; they are the foreign keys in the way.
        val nostrEventId = "e$owner".take(64)
        db.nostrEventDao().upsert(
            NostrEvent(
                id = nostrEventId,
                pubKey = owner,
                kind = 0,
                tags = emptyArray(),
                content = "{}",
                sig = "0".repeat(128)
            )
        )
        db.profileDao().upsert(Profile(publicKey = owner, nostrEventId = nostrEventId))

        val chatRoom = ChatRoom(
            id = roomId,
            userPublicKey = owner,
            subject = "#Ekklesia",
            description = SharedKeyDerivation.describe("Admins of the group."),
            mlsGroupState = null
        )
        db.chatRoomDao().upsert(chatRoom)

        db.dkgSessionDao().upsert(
            DkgSession(
                id = ceremonyId,
                chatRoomId = roomId,
                coordinatorPublicKey = alice,
                userPublicKey = owner,
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
                DkgParticipantMessage(
                    sessionId = ceremonyId,
                    participantPublicKey = member,
                    kind = DkgRitualEvents.HOST_KEY,
                    payload = hostKeys[i]
                )
            )
        }

        room = LocalChatRoom(chatRoom = chatRoom)
    }

    /** Alice proposes, which makes her device the coordinator and agrees on her behalf. */
    private suspend fun propose(): FrostSigningSession = FrostSigningManager.proposeSigning(
        database = db,
        localChatRoom = room,
        userPublicKey = alice,
        kind = 1,
        tags = arrayOf(),
        content = "Something for the group to say"
    )

    /**
     * A session as it sits on a member's device once Alice's proposal has
     * arrived: recorded, awaiting the owner's answer, and nothing published.
     * Written straight to the row so the test can put a device in that state
     * without standing up the inbound path.
     */
    private suspend fun proposalArrivedAt(owner: HexKey): FrostSigningSession {
        val session = FrostSigningSession(
            id = "s".repeat(64),
            chatRoomId = roomId,
            coordinatorPublicKey = alice,
            userPublicKey = owner,
            dkgSessionId = ceremonyId,
            threshold = threshold,
            participantCount = participants,
            signerId = members.indexOf(owner),
            derivationPath = SharedKeyDerivation.formatPath(SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH)
        )
        db.frostSigningSessionDao().upsert(session)
        db.frostSigningSessionDao().upsertItems(
            listOf(
                FrostSigningItem(
                    sessionId = session.id,
                    itemIndex = 0,
                    unsignedEventJson = "{}",
                    eventId = "e".repeat(64),
                    nonceRandom = "f".repeat(64)
                )
            )
        )
        return session
    }

    /** One signing message from [member], as the inbound path would hand it over. */
    private suspend fun receive(sessionId: String, kind: Kind, member: HexKey, content: String = "") {
        FrostSigningManager.processSigningPayload(
            database = db,
            localChatRoom = room,
            innerEvent = Event(
                // The id is not read by anything here; the inbound path stores the
                // event under it before dispatching, which this test does not do.
                id = "$kind$member".hashCode().toUInt().toString(16).padStart(64, '0'),
                pubKey = member,
                createdAt = Clock.System.now().epochSeconds,
                kind = kind,
                tags = FrostSigningEvents.assembleTags(sessionId),
                content = content,
                sig = ""
            ),
            userPublicKey = room.chatRoom.userPublicKey
        )
    }

    private suspend fun refusalFrom(sessionId: String, member: HexKey) =
        receive(sessionId, FrostSigningEvents.DECLINE, member, "Declined to sign")

    private suspend fun session(id: String) = db.frostSigningSessionDao().getSessionById(id)!!

    private suspend fun transcript(type: String) = db.chatMessageDao()
        .getChatMessagesByChatRoomId(roomId)
        .map { it.chatMessage }
        .filter { it.messageType == type }

    /** What this device has queued for the group, of one kind. The room is NIP-17 here. */
    private suspend fun queued(kind: Kind) = db.giftWrapPayloadDao()
        .getByChatRoomAndKinds(roomId, listOf(kind))
        .filter { it.publicKey == room.chatRoom.userPublicKey }

    @Test
    fun `one refusal in a 2-of-3 group leaves the session open`() = runBlocking {
        openDevice()
        val session = propose()

        refusalFrom(session.id, bob)

        // The point of a threshold: Alice and Carol can still sign this without
        // Bob, so his no ends nothing.
        val after = session(session.id)
        assertEquals(FrostSigningStage.COLLECTING_NONCES, after.stage)
        assertNull(after.failureReason)

        // It is on file, counted, and said in the room -- with the arithmetic,
        // so the group can see how close to ending the session now is.
        assertNotNull(db.frostSigningSessionDao().getMessage(session.id, FrostSigningEvents.DECLINE, bob))
        val declined = transcript(ChatMessage.TYPE_FROST_DECLINED)
        assertEquals(1, declined.size)
        assertEquals(bob, declined.single().senderPublicKey)
        assertTrue(declined.single().content.contains("2 could still sign"), declined.single().content)
        assertEquals(emptyList(), transcript(ChatMessage.TYPE_FROST_FAILED))
    }

    @Test
    fun `the second refusal is the one that abandons it`() = runBlocking {
        openDevice()
        val session = propose()

        refusalFrom(session.id, bob)
        refusalFrom(session.id, carol)

        // Alice alone is fewer than the two it takes.
        val after = session(session.id)
        assertEquals(FrostSigningStage.FAILED, after.stage)
        assertEquals(
            "Abandoned: 2 of 3 members declined to sign, leaving fewer than the 2 it takes.",
            after.failureReason
        )

        // Bob's refusal was one the session survived and reads as one; Carol's
        // is the one that ended it, and says both things. Nothing is blamed on
        // a member for what was the group's arithmetic -- there is no FAILURE
        // on the wire, because every device reaches the same count on its own.
        val declined = transcript(ChatMessage.TYPE_FROST_DECLINED)
        assertEquals(listOf(bob), declined.map { it.senderPublicKey })

        val failed = transcript(ChatMessage.TYPE_FROST_FAILED)
        assertEquals(listOf(carol), failed.map { it.senderPublicKey })
        assertTrue(failed.single().content.startsWith("declined to sign."), failed.single().content)
        assertTrue(failed.single().content.contains("abandoned"), failed.single().content)
        assertEquals(emptyList(), queued(FrostSigningEvents.FAILURE))
    }

    @Test
    fun `a redelivered refusal is counted once`() = runBlocking {
        openDevice()
        val session = propose()

        // Relays redeliver, and a member catching up replays their backlog.
        // The same no twice is still one member out of the n.
        refusalFrom(session.id, bob)
        refusalFrom(session.id, bob)

        assertEquals(FrostSigningStage.COLLECTING_NONCES, session(session.id).stage)
        assertEquals(1, transcript(ChatMessage.TYPE_FROST_DECLINED).size)
    }

    @Test
    fun `a refusal from somebody who holds no share takes nobody out of the n`() = runBlocking {
        openDevice()
        val session = propose()

        // A subgroup's ceremony runs in the parent's room over a subset of it,
        // so a signing message can come from a member who was never in the
        // ceremony. They could not have signed, so their refusing to changes
        // what the key can do by nothing.
        refusalFrom(session.id, bob)
        refusalFrom(session.id, stranger)

        assertEquals(FrostSigningStage.COLLECTING_NONCES, session(session.id).stage)
        assertNull(db.frostSigningSessionDao().getMessage(session.id, FrostSigningEvents.DECLINE, stranger))
        assertEquals(listOf(bob), transcript(ChatMessage.TYPE_FROST_DECLINED).map { it.senderPublicKey })
    }

    @Test
    fun `refusals arriving after the group has signed abandon nothing`() = runBlocking {
        openDevice()
        val session = propose()
        db.frostSigningSessionDao().upsert(session(session.id).copy(stage = FrostSigningStage.COMPLETE))

        // Bob and Carol each said no before their device had seen the
        // signature. What they refused no longer needed them, and a signature
        // that exists is not un-made by anything that arrives afterwards.
        refusalFrom(session.id, bob)
        refusalFrom(session.id, carol)

        assertEquals(FrostSigningStage.COMPLETE, session(session.id).stage)
        assertEquals(emptyList(), transcript(ChatMessage.TYPE_FROST_FAILED))
    }

    @Test
    fun `a failure arriving after the group has signed abandons nothing either`() = runBlocking {
        openDevice()
        val session = propose()
        db.frostSigningSessionDao().upsert(session(session.id).copy(stage = FrostSigningStage.COMPLETE))

        // The same guard, for the message a build predating the refusal kind
        // still sends when its owner declines: it used to flip a finished
        // session to failed, reporting a signature that exists as one that
        // does not.
        receive(session.id, FrostSigningEvents.FAILURE, bob, "Declined to sign")

        assertEquals(FrostSigningStage.COMPLETE, session(session.id).stage)
        assertEquals(emptyList(), transcript(ChatMessage.TYPE_FROST_FAILED))
    }

    @Test
    fun `an old build's refusal still ends the session, as it always did`() = runBlocking {
        openDevice()
        val session = propose()

        // A device that predates the refusal kind sends a FAILURE when its
        // owner declines, and there is no telling that apart from a fault.
        // It is honoured as the abandonment it always was rather than guessed
        // at; the cost of a mixed-version group is the old behaviour, not a
        // stall.
        receive(session.id, FrostSigningEvents.FAILURE, bob, "Declined to sign")

        assertEquals(FrostSigningStage.FAILED, session(session.id).stage)
    }

    @Test
    fun `declining on this device says so, and leaves the session open`() = runBlocking {
        openDevice(owner = bob)
        val session = proposalArrivedAt(bob)

        FrostSigningManager.decline(db, room, session.id)

        // Bob's answer is on his row, published to the group, and counted on
        // his own device the way it will be on every other. The session is
        // still open around him: Alice and Carol can sign it.
        val after = session(session.id)
        assertNotNull(after.signDeclinedAt)
        assertNull(after.signApprovedAt)
        assertEquals(FrostSigningStage.COLLECTING_NONCES, after.stage)
        assertNotNull(db.frostSigningSessionDao().getMessage(session.id, FrostSigningEvents.DECLINE, bob))
        assertEquals(1, queued(FrostSigningEvents.DECLINE).size)
        assertEquals(emptyList(), queued(FrostSigningEvents.NONCE))
        assertEquals(emptyList(), queued(FrostSigningEvents.FAILURE))

        // He is not asked again: the decision was his to make once.
        assertFalse(FrostSigningManager.isAwaitingApproval(after, db.frostSigningSessionDao().getItems(session.id)))

        val declined = transcript(ChatMessage.TYPE_FROST_DECLINED).single()
        assertTrue(declined.isUserMessage)

        // Carol's refusal, arriving afterwards, is the second of two -- and the
        // one that ends it, on Bob's device as on everybody's.
        refusalFrom(session.id, carol)
        assertEquals(FrostSigningStage.FAILED, session(session.id).stage)
        assertEquals(listOf(carol), transcript(ChatMessage.TYPE_FROST_FAILED).map { it.senderPublicKey })
    }

    @Test
    fun `declining on this device as the last member who could is what abandons it`() = runBlocking {
        openDevice(owner = bob)
        val session = proposalArrivedAt(bob)

        refusalFrom(session.id, carol)
        assertEquals(FrostSigningStage.COLLECTING_NONCES, session(session.id).stage)

        FrostSigningManager.decline(db, room, session.id)

        // The refusal still goes out -- the others count it the same way -- and
        // the ending is written in Bob's own name, since his was the answer that
        // settled it.
        assertEquals(FrostSigningStage.FAILED, session(session.id).stage)
        assertEquals(1, queued(FrostSigningEvents.DECLINE).size)
        assertEquals(emptyList(), queued(FrostSigningEvents.FAILURE))
        assertTrue(transcript(ChatMessage.TYPE_FROST_FAILED).single().isUserMessage)
    }

    @Test
    fun `a refusal decided but never sent goes out on the next message`() = runBlocking {
        openDevice(owner = bob)
        val session = proposalArrivedAt(bob)

        // The device died between writing the decision and publishing it: the
        // row says Bob refused and nothing of his is on file or on the wire.
        db.frostSigningSessionDao().upsert(session.copy(signDeclinedAt = Clock.System.now()))
        assertEquals(emptyList(), queued(FrostSigningEvents.DECLINE))

        // Any message advancing the session is enough -- the same way a nonce
        // an approval never got to publish goes out on the next pass.
        receive(session.id, FrostSigningEvents.NONCE, carol, "00".repeat(66))

        assertEquals(1, queued(FrostSigningEvents.DECLINE).size)
        assertNotNull(db.frostSigningSessionDao().getMessage(session.id, FrostSigningEvents.DECLINE, bob))
        assertEquals(FrostSigningStage.COLLECTING_NONCES, session(session.id).stage)
    }

    @Test
    fun `who can answer is read off the ceremony, not off the room`() = runBlocking {
        openDevice()
        val session = propose()

        // A member invited to the room after the key was made holds no share:
        // the proposal is dropped before it becomes a session on their device,
        // and no refusal or nonce of theirs would count. What the screen lists
        // is who put a host key into the ceremony -- the same rows the manager
        // places signer ids over -- and the room's roster is never consulted.
        val shareHolders = DatabaseFrostSigningRepository(db, this)
            .observeShareHolders(session.dkgSessionId)
            .first()

        assertEquals(setOf(alice, bob, carol), shareHolders)
    }

    @Test
    fun `a refusal after an approval is ignored`() = runBlocking {
        openDevice(owner = bob)
        val session = proposalArrivedAt(bob)

        FrostSigningManager.approve(db, room, session.id)
        FrostSigningManager.decline(db, room, session.id)

        // A stale screen cannot take back a nonce that has gone out. The session
        // holds one answer from this device, and it is the first one.
        val after = session(session.id)
        assertNotNull(after.signApprovedAt)
        assertNull(after.signDeclinedAt)
        assertEquals(emptyList(), queued(FrostSigningEvents.DECLINE))
    }
}
