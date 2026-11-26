package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
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
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.DkgParticipantMessage
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.FrostSigningItem
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.database.model.types.FrostSigningSubject
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents

/**
 * Dedun declines a profile or a post that nothing would show once signed, out loud
 * (docs/group-nostr-profile-and-posts.md, D5).
 *
 * The harness is WalletDeclineJvmTest's: each proposal is a real one, made on Alice's
 * device -- which stands in for a Curare member's, since proposing checks nothing --
 * and handed to Bob's through the inbound path, because the event ids are rebuilt
 * there and a fabricated proposal would be dropped before the check under test ran.
 */
class GroupStatementDeclineJvmTest {
    private val participants = 3
    private val threshold = 2

    private val keyMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(
            ByteVector32("1c0ffee0000000000000000000000000000000000000000000000000000000a1")
        ),
        nParticipants = participants,
        threshold = threshold
    )

    private val thresholdPublicKey = keyMaterial.thresholdPublicKey.value.toHex()
    private val roomId = SharedKeyDerivation.marmotGroupId(thresholdPublicKey)
    private val ceremonyId = "ceremony".padEnd(64, '0')
    private val members = listOf("a", "b", "c").map { it.repeat(64) }
    private val hostKeys = listOf("2a", "2b", "2c").map { it.padEnd(66, '0') }

    private val alice = members[0]
    private val bob = members[1]

    private lateinit var db: DedunDatabase
    private lateinit var room: LocalChatRoom

    @AfterTest
    fun closeDatabase() {
        if (::db.isInitialized) db.close()
    }

    private suspend fun openDevice(owner: HexKey) {
        if (::db.isInitialized) db.close()
        db = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

        val index = members.indexOf(owner)
        val nostrEventId = "e$owner".take(64)
        db.nostrEventDao().upsert(
            NostrEvent(id = nostrEventId, pubKey = owner, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128))
        )
        db.profileDao().upsert(Profile(publicKey = owner, nostrEventId = nostrEventId))

        val chatRoom = ChatRoom(
            id = roomId,
            userPublicKey = owner,
            subject = "Ekklesia",
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
                DkgParticipantMessage(sessionId = ceremonyId, participantPublicKey = member, kind = DkgRitualEvents.HOST_KEY, payload = hostKeys[i])
            )
        }

        room = LocalChatRoom(chatRoom = chatRoom)
    }

    /** What this device has queued for the group, of one kind. The room is NIP-17 here. */
    private suspend fun queued(kind: Kind) = db.giftWrapPayloadDao()
        .getByChatRoomAndKinds(roomId, listOf(kind))
        .filter { it.publicKey == room.chatRoom.userPublicKey }

    /**
     * Alice's device proposes one event of [kind]; Bob's device receives the proposal.
     * Returns the session id, with [db] and [room] left on Bob's device.
     */
    private suspend fun proposalReachesBob(kind: Kind, tags: Array<Array<String>> = arrayOf(), content: String = "{}"): String {
        openDevice(alice)
        val session = FrostSigningManager.proposeSigning(
            database = db,
            localChatRoom = room,
            userPublicKey = alice,
            kind = kind,
            tags = tags,
            content = content
        )
        val proposal = queued(FrostSigningEvents.PROPOSAL).single().content

        openDevice(bob)
        FrostSigningManager.processSigningPayload(
            database = db,
            localChatRoom = room,
            innerEvent = Event(
                id = "proposal".padEnd(64, '0'),
                pubKey = alice,
                createdAt = Clock.System.now().epochSeconds,
                kind = FrostSigningEvents.PROPOSAL,
                tags = FrostSigningEvents.assembleTags(sessionId = session.id, dkgSessionId = ceremonyId),
                content = proposal,
                sig = ""
            ),
            userPublicKey = bob
        )
        return session.id
    }

    private suspend fun assertDeclinedAsUnshowable(sessionId: String) {
        val session = db.frostSigningSessionDao().getSessionById(sessionId)
        assertNotNull(session, "declined, not dropped: the session is filed so the decline can go out")
        assertNotNull(session.signDeclinedAt)
        assertNull(session.signApprovedAt)
        assertFalse(FrostSigningManager.isAwaitingApproval(session, db.frostSigningSessionDao().getItems(sessionId)), "and nobody is asked")

        val declines = queued(FrostSigningEvents.DECLINE)
        assertEquals(1, declines.size, "the decline is published, so the group counts it")
        assertEquals(GroupStatementRefusal.REASON, declines.single().content)
        assertEquals(emptyList(), queued(FrostSigningEvents.NONCE), "and nothing of the signature goes out")
    }

    @Test
    fun `a profile no reader could parse is declined on arrival, out loud`() = runBlocking {
        assertDeclinedAsUnshowable(proposalReachesBob(kind = MetadataEvent.KIND, content = "not json"))
    }

    @Test
    fun `a post with nothing in it is declined on arrival`() = runBlocking {
        assertDeclinedAsUnshowable(proposalReachesBob(kind = TextNoteEvent.KIND, content = "  \n "))
    }

    @Test
    fun `a readable profile is put to the member`() = runBlocking {
        assertAsked(proposalReachesBob(kind = MetadataEvent.KIND, content = """{"name":"Translation collective"}"""))
    }

    @Test
    fun `an empty profile is a profile, and is put to the member`() = runBlocking {
        // Wiping its profile is something a group is entitled to do.
        assertAsked(proposalReachesBob(kind = MetadataEvent.KIND, content = ""))
    }

    @Test
    fun `a post with something to say is put to the member`() = runBlocking {
        assertAsked(proposalReachesBob(kind = TextNoteEvent.KIND, content = "We meet on Thursday"))
    }

    @Test
    fun `approving a session an older build filed for an empty post declines it instead`() = runBlocking {
        openDevice(bob)
        val session = FrostSigningSession(
            id = "s".repeat(64),
            chatRoomId = roomId,
            coordinatorPublicKey = alice,
            userPublicKey = bob,
            dkgSessionId = ceremonyId,
            threshold = threshold,
            participantCount = participants,
            signerId = members.indexOf(bob),
            derivationPath = SharedKeyDerivation.formatPath(SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH),
            subject = FrostSigningSubject.EVENTS,
        )
        val blank = Event(
            id = "e".repeat(64),
            pubKey = roomId,
            createdAt = 1,
            kind = TextNoteEvent.KIND,
            tags = emptyArray(),
            content = "",
            sig = "",
        )
        db.frostSigningSessionDao().upsert(session)
        db.frostSigningSessionDao().upsertItems(
            listOf(FrostSigningItem(sessionId = session.id, itemIndex = 0, unsignedEventJson = blank.toJson(), eventId = blank.id, nonceRandom = "f".repeat(64)))
        )

        FrostSigningManager.approve(db, room, session.id)

        assertDeclinedAsUnshowable(session.id)
    }

    private suspend fun assertAsked(sessionId: String) {
        val session = db.frostSigningSessionDao().getSessionById(sessionId)
        assertNotNull(session)
        assertNull(session.signDeclinedAt)
        assertTrue(FrostSigningManager.isAwaitingApproval(session, db.frostSigningSessionDao().getItems(sessionId)))
        assertEquals(emptyList(), queued(FrostSigningEvents.DECLINE))
    }
}
