package com.machankura.dedun.compose.database.dao

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.crypto.frost.Frost
import fr.acinq.bitcoin.crypto.frost.IndividualNonce
import fr.acinq.bitcoin.crypto.frost.KeyMaterial
import fr.acinq.bitcoin.crypto.frost.SecretNonce
import fr.acinq.bitcoin.crypto.frost.Session
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.managers.SharedKeyDerivation
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent

/**
 * The table that keeps what a quorum signed, checked against a real quorum.
 *
 * A fake signature would pass every column assertion here and prove nothing, so
 * the events are signed by an actual t-of-n key derived at the room's own path
 * -- the same construction `ChronicleApplyJvmTest` uses. That is what makes
 * [GroupSignedEvent.verifies] worth asserting: it is answered from the row
 * alone, and it is the only assertion that would notice a column quietly
 * failing to round-trip.
 */
class GroupSignedEventDaoJvmTest {
    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun closeDb() = db.close()

    private val participants = 3
    private val threshold = 2

    private val keyMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(
            ByteVector32("1c0ffee0000000000000000000000000000000000000000000000000000000a1")
        ),
        nParticipants = participants,
        threshold = threshold
    )

    private val room: SharedKeyDerivation.Derived = SharedKeyDerivation.derive(
        thresholdPublicKey = keyMaterial.thresholdPublicKey.value.toHex(),
        path = SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH
    )

    /** The room's id and the key it signs as are one value. */
    private val chatRoomId = room.hex

    private val derivationPath = SharedKeyDerivation.formatPath()

    private val member = "8".repeat(64)

    private fun groupSignature(eventId: String): String {
        val cache = room.cache
        val message = ByteVector(eventId.hexToByteArray())
        val signerIds = listOf(0, 1)

        val nonces = signerIds.map { signerId ->
            SecretNonce.generate(
                sessionRandom = ByteVector32("a".repeat(63) + "${signerId + 1}"),
                secretShare = keyMaterial.secretShares[signerId],
                publicShare = keyMaterial.publicShares[signerId],
                tweakedThresholdPublicKey = cache.tweakedPublicKey,
                message = message,
                extraInput = null
            )
        }

        val signingSession = Session.create(
            aggregatedNonce = IndividualNonce.aggregate(nonces.map { it.second }).right!!,
            signerIds = signerIds.map { it.toUInt() },
            signerPublicShares = signerIds.map { keyMaterial.publicShares[it] },
            nParticipants = participants,
            threshold = threshold,
            tweakCache = cache,
            message = message
        )

        val partials = signerIds.mapIndexed { position, signerId ->
            signingSession.sign(
                nonces[position].first,
                keyMaterial.secretShares[signerId],
                signerId.toUInt()
            ).right!!
        }

        return signingSession.aggregateSigs(partials).right!!.toByteArray().toHex()
    }

    /** A template as a finished signing session leaves it, authored by the room. */
    private fun signed(template: EventTemplate<*>): Event {
        val id = EventHasher.hashId(
            pubKey = chatRoomId,
            createdAt = template.createdAt,
            kind = template.kind,
            tags = template.tags,
            content = template.content
        )

        return Event(
            id = id,
            pubKey = chatRoomId,
            createdAt = template.createdAt,
            kind = template.kind,
            tags = template.tags,
            content = template.content,
            sig = groupSignature(id)
        )
    }

    private val post = signed(
        TextNoteEvent.build(note = "isiZulu", createdAt = 1_700_000_000L)
    )

    /**
     * The room, and the two rows it hangs off. `ChatRoom.userPublicKey` is a
     * foreign key onto `Profile`, which is one onto `NostrEvent`, so the room
     * cannot exist on its own -- and neither can a `GroupSignedEvent`, which is
     * the constraint this seeding is really here to satisfy.
     */
    private suspend fun seedRoom() {
        val nostrEventId = "c".repeat(64)
        db.nostrEventDao().upsert(
            NostrEvent(
                id = nostrEventId,
                pubKey = member,
                kind = 0,
                tags = emptyArray(),
                content = "{}",
                sig = "0".repeat(128),
            )
        )
        db.profileDao().upsert(
            Profile(publicKey = member, userName = "member", nostrEventId = nostrEventId)
        )
        db.chatRoomDao().upsert(
            ChatRoom(
                id = chatRoomId,
                userPublicKey = member,
                subject = "#Ekklesia",
                description = SharedKeyDerivation.describe("Admins"),
                mlsGroupState = null,
            )
        )
    }

    private fun rowFor(
        event: Event = post,
        path: String? = derivationPath,
        sessionId: String? = "session-1",
    ) = GroupSignedEvent.fromEvent(
        event = event,
        chatRoomId = chatRoomId,
        derivationPath = path,
        frostSigningSessionId = sessionId,
    )

    @Test
    fun `a signed event survives the round trip and still verifies`() = runBlocking {
        seedRoom()
        db.groupSignedEventDao().record(rowFor())

        val stored = db.groupSignedEventDao().getById(post.id)
        assertNotNull(stored, "the event just recorded could not be read back")

        assertEquals(post.pubKey, stored.publicKey)
        assertEquals(post.kind, stored.kind)
        assertEquals(post.content, stored.content)
        assertEquals(post.sig, stored.signature)
        assertEquals(post.createdAt, stored.createdAt.epochSeconds)
        assertContentEquals(post.tags.map { it.toList() }, stored.tags.map { it.toList() })

        // The claim the columns exist to support: the group's signature is still
        // checkable from the row, with nothing else on hand.
        assertTrue(stored.verifies(), "the stored row no longer verifies as the room's")
        assertEquals(post.id, stored.toEvent().id)
    }

    @Test
    fun `the path the room was derived at is on the row`() = runBlocking {
        seedRoom()
        db.groupSignedEventDao().record(rowFor())

        val stored = db.groupSignedEventDao().getById(post.id)
        assertNotNull(stored)

        assertEquals(derivationPath, stored.derivationPath)
        assertEquals(SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH, stored.pathIndices())

        // The path is the whole point: walking it from the ceremony's threshold
        // key has to arrive back at the author of the event that was stored.
        assertEquals(
            stored.publicKey,
            SharedKeyDerivation.derive(
                thresholdPublicKey = keyMaterial.thresholdPublicKey.value.toHex(),
                path = stored.pathIndices()
            ).hex
        )
    }

    @Test
    fun `an untweaked room records no path rather than a wrong one`() = runBlocking {
        seedRoom()
        db.groupSignedEventDao().record(rowFor(path = null))

        val stored = db.groupSignedEventDao().getById(post.id)
        assertNotNull(stored)

        assertNull(stored.derivationPath)
        // Empty, not null: no walk at all is what a room signing as the
        // threshold key itself did, and it is a path `derive` accepts.
        assertTrue(stored.pathIndices().isEmpty())
    }

    @Test
    fun `a chronicle arrival does not empty what the session knew`() = runBlocking {
        seedRoom()
        db.groupSignedEventDao().record(rowFor())

        // The same event again, as a page hands it to a member who holds no key
        // state and never ran the session.
        db.groupSignedEventDao().record(rowFor(path = null, sessionId = null))

        val stored = db.groupSignedEventDao().getById(post.id)
        assertNotNull(stored)

        assertEquals(derivationPath, stored.derivationPath)
        assertEquals("session-1", stored.frostSigningSessionId)
        assertEquals(1, db.groupSignedEventDao().countByChatRoomId(chatRoomId))
    }

    @Test
    fun `a later arrival fills in what the first one could not say`() = runBlocking {
        seedRoom()
        db.groupSignedEventDao().record(rowFor(path = null, sessionId = null))
        db.groupSignedEventDao().record(rowFor())

        val stored = db.groupSignedEventDao().getById(post.id)
        assertNotNull(stored)

        assertEquals(derivationPath, stored.derivationPath)
        assertEquals("session-1", stored.frostSigningSessionId)
    }

    @Test
    fun `a batch is filed together and reads back oldest first`() = runBlocking {
        seedRoom()

        val profile = signed(
            MetadataEvent.createNew(name = "In Detention", createdAt = 1_700_000_010L)
        )

        db.groupSignedEventDao().recordAll(
            listOf(rowFor(event = profile), rowFor(event = post))
        )

        val dao = db.groupSignedEventDao()
        assertEquals(
            listOf(post.id, profile.id),
            dao.getByChatRoomId(chatRoomId).map { it.id },
            "a room's signed work has to come back in the order it can be applied in"
        )
        assertEquals(
            listOf(post.id),
            dao.getByChatRoomIdAndKind(chatRoomId, TextNoteEvent.KIND).map { it.id }
        )
        assertEquals(2, dao.getByFrostSigningSessionId("session-1").size)
    }

    @Test
    fun `a row whose columns have drifted stops verifying`() = runBlocking {
        seedRoom()
        db.groupSignedEventDao().record(rowFor().copy(content = "isiXhosa"))

        val stored = db.groupSignedEventDao().getById(post.id)
        assertNotNull(stored)

        // The id is the hash of the fields, so an edited row is an event the
        // group never signed. Nothing else has to be consulted to know that.
        assertFalse(stored.verifies(), "an event with edited content must not verify")
    }
}
