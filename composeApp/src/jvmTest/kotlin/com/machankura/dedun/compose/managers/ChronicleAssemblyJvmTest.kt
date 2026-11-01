package com.machankura.dedun.compose.managers

import androidx.room3.Room
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import com.vitorpamplona.quartz.nip65RelayList.AdvertisedRelayListEvent
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.chronicle.ChronicleEvent
import com.machankura.dedun.compose.nostr.chronicle.TEST_KINDS
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent

/**
 * Assembling a room's chronicle out of what a device actually holds.
 *
 * The seed is the real path a finished signing session takes -- the events filed
 * in `GroupSignedEvent`, then handed to `ChatMessage.applyInnerEvent` -- so what is
 * chronicled here is what a member's database really contains rather than rows
 * built to suit the test. The record is the only source: nothing is rebuilt from
 * rows, so what a chronicle carries is byte for byte what the group signed.
 *
 * The app's own list of kinds is empty, so these assemble with [TEST_KINDS]; the
 * last case holds the default to assembling nothing.
 */
class ChronicleAssemblyJvmTest {
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

    private val newMember = "9".repeat(64)

    private fun groupSignature(eventId: String): String {
        val message = ByteVector(eventId.hexToByteArray())
        val signerIds = listOf(0, 1)

        val nonces = signerIds.map { signerId ->
            SecretNonce.generate(
                sessionRandom = ByteVector32("a".repeat(63) + "${signerId + 1}"),
                secretShare = keyMaterial.secretShares[signerId],
                publicShare = keyMaterial.publicShares[signerId],
                tweakedThresholdPublicKey = room.cache.tweakedPublicKey,
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
            tweakCache = room.cache,
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

    /** A template as a finished session leaves it: authored and signed by the room. */
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

    /** Every event [seed] has been given, in the order it was given them. */
    private val seeded = mutableListOf<Event>()

    /**
     * What `FrostSigningManager.complete` does with a finished batch: file the
     * event as the group signed it, then apply it through the same call.
     */
    private suspend fun seed(template: EventTemplate<*>): Event {
        val event = signed(template)

        record(event)
        ChatMessage.applyInnerEvent(
            database = db,
            groupId = chatRoomId,
            event = event,
            marmotGroupEventId = null,
            marmotInnerEventId = null,
            senderPublicKey = chatRoomId,
            isUserMessage = false,
            createdAt = Instant.fromEpochSeconds(event.createdAt),
            groupSignedEventId = event.id,
        )

        seeded += event

        return event
    }

    /** What `FrostSigningManager.recordSignedEvents` does, through the same table. */
    private suspend fun record(vararg events: Event) {
        db.groupSignedEventDao().recordAll(
            events.map {
                GroupSignedEvent.fromEvent(
                    event = it,
                    chatRoomId = chatRoomId,
                    derivationPath = SharedKeyDerivation.formatPath(),
                    frostSigningSessionId = "session-1",
                )
            }
        )
    }

    /** ChatRoom -> Profile -> NostrEvent, the foreign key chain a room hangs off. */
    private suspend fun seedRoom() {
        val nostrEventId = "c".repeat(64)
        db.nostrEventDao().upsert(
            NostrEvent(
                id = nostrEventId,
                pubKey = newMember,
                kind = 0,
                tags = emptyArray(),
                content = "{}",
                sig = "0".repeat(128),
            )
        )
        db.profileDao().upsert(
            Profile(publicKey = newMember, userName = "member", nostrEventId = nostrEventId)
        )
        db.chatRoomDao().upsert(
            ChatRoom(
                id = chatRoomId,
                userPublicKey = newMember,
                subject = "#Ekklesia",
                description = null,
                mlsGroupState = null,
            )
        )
    }

    private fun template(kind: Int, createdAt: Long, content: String, tags: Array<Array<String>> = emptyArray()) =
        EventTemplate<Event>(createdAt = createdAt, kind = kind, tags = tags, content = content)

    /**
     * A room that has signed a relay list, a profile and two posts, seeded posts
     * first -- the reverse of the order they apply in, so that the chronicle being
     * in order is something assembly did.
     */
    private suspend fun seedSignedWork() {
        seedRoom()

        seed(template(TextNoteEvent.KIND, 1_700_000_000L, "The group says hello."))
        seed(template(TextNoteEvent.KIND, 1_700_000_001L, "And says it again."))
        seed(template(MetadataEvent.KIND, 1_700_000_010L, "{\"name\":\"Ekklesia\"}"))
        seed(
            template(
                AdvertisedRelayListEvent.KIND, 1_700_000_020L, "",
                tags = arrayOf(arrayOf("r", "wss://relay.example.com")),
            )
        )
    }

    private suspend fun chronicledPayloads(): List<Event> =
        ChronicleManager.assemble(db, chatRoomId, newMember, kinds = TEST_KINDS)
            .flatMap { assertNotNull(ChronicleEvent.decodePage(it.content)) }

    /**
     * Nothing leaves that the recipient could not check for themselves -- which
     * includes a row on file whose fields have drifted from the event it
     * recorded. Its id and signature are the group's; what they no longer cover
     * is what it says.
     */
    @Test
    fun `every payload in a chronicle is one the room signed`() = runBlocking {
        seedSignedWork()

        val drifted = signed(template(TextNoteEvent.KIND, 1_700_000_030L, "What the group said."))
        db.groupSignedEventDao().record(
            GroupSignedEvent.fromEvent(event = drifted, chatRoomId = chatRoomId)
                .copy(content = "What somebody wishes it had said.")
        )

        val payloads = chronicledPayloads()

        assertTrue(payloads.isNotEmpty(), "a room with signed work chronicles something")
        payloads.forEach {
            assertTrue(
                GroupKeyStateEvent.isSignedByRoom(it, chatRoomId),
                "kind ${it.kind} (${it.id.take(8)}) was chronicled without a signature the room can be checked for"
            )
        }
        assertTrue(payloads.none { it.id == drifted.id }, "the drifted row should have been left out")
        assertEquals(seeded.map { it.id }.toSet(), payloads.map { it.id }.toSet())
    }

    @Test
    fun `a chronicle holds every kind the group signed, and each of them once`() = runBlocking {
        seedSignedWork()

        val byKind = chronicledPayloads().groupBy { it.kind }

        assertEquals(1, byKind[AdvertisedRelayListEvent.KIND]?.size)
        assertEquals(1, byKind[MetadataEvent.KIND]?.size)
        assertEquals(2, byKind[TextNoteEvent.KIND]?.size)

        assertEquals(TEST_KINDS.all, byKind.keys)
    }

    @Test
    fun `a chronicle is in the list's order across its whole length`() = runBlocking {
        seedSignedWork()

        val ranks = chronicledPayloads().map { assertNotNull(TEST_KINDS.applyRank(it.kind)) }

        // Seeded in the reverse order, so a chronicle that kept the table's order
        // would fail here. In the app this is a foreign key violation on the
        // receiver rather than a wrong answer.
        assertEquals(ranks.sorted(), ranks, "a chronicle out of order is applied out of order")
    }

    @Test
    fun `a member's own rumor is not part of the group's record`() = runBlocking {
        seedSignedWork()

        // A member's own kind:1, travelling through the room the way everything
        // on the wire does: stored as an inner event and applied. It is real
        // content in the room and the group never signed it, so it cannot be
        // handed to somebody who has no way to check it.
        val rumor = Event(
            id = EventHasher.hashId(
                pubKey = newMember, createdAt = 1_700_000_060L, kind = TextNoteEvent.KIND,
                tags = emptyArray(), content = "My own note."
            ),
            pubKey = newMember, createdAt = 1_700_000_060L, kind = TextNoteEvent.KIND,
            tags = emptyArray(), content = "My own note.", sig = ""
        )
        db.marmotInnerEventDao().upsert(
            MarmotInnerEvent(
                id = rumor.id,
                publicKey = rumor.pubKey,
                kind = rumor.kind,
                createdAt = Instant.fromEpochSeconds(rumor.createdAt),
                tags = rumor.tags,
                content = rumor.content,
                chatRoomId = chatRoomId,
            )
        )
        ChatMessage.applyInnerEvent(
            database = db,
            groupId = chatRoomId,
            event = rumor,
            marmotGroupEventId = null,
            marmotInnerEventId = rumor.id,
            senderPublicKey = newMember,
            isUserMessage = true,
            createdAt = Instant.fromEpochSeconds(rumor.createdAt),
        )

        assertEquals(
            1,
            db.marmotInnerEventDao().getByChatRoomAndKinds(chatRoomId, listOf(TextNoteEvent.KIND)).size,
            "the rumor should be on disk"
        )
        assertEquals(
            2,
            chronicledPayloads().count { it.kind == TextNoteEvent.KIND },
            "only the group's two posts should have been chronicled"
        )
        assertTrue(chronicledPayloads().none { it.id == rumor.id })
    }

    @Test
    fun `a room with nothing signed chronicles nothing, and says so rather than failing`() = runBlocking {
        seedRoom()

        assertEquals(emptyList(), ChronicleManager.assemble(db, chatRoomId, newMember, kinds = TEST_KINDS))
    }

    @Test
    fun `a chronicle of one page still says it is one of one`() = runBlocking<Unit> {
        seedSignedWork()

        val pages = ChronicleManager.assemble(db, chatRoomId, newMember, kinds = TEST_KINDS)

        assertEquals(1, pages.size, "this much work fits one page")

        val page = ChronicleEvent(
            id = "d".repeat(64),
            pubKey = chatRoomId,
            createdAt = pages.single().createdAt,
            tags = pages.single().tags,
            content = pages.single().content,
            sig = ""
        )

        assertEquals(0, page.page()?.index)
        assertEquals(1, page.page()?.count)
        assertEquals(newMember, page.recipient())
        assertNotNull(page.chronicleId())
    }

    /**
     * The events, not a reconstruction of them.
     *
     * Byte-for-byte against what was signed. Exact by construction: nothing is
     * rebuilt, so there is no tag order to get wrong and no field a row could
     * have failed to keep.
     */
    @Test
    fun `a chronicle carries the group's events as the group signed them`() = runBlocking {
        seedSignedWork()

        val payloads = chronicledPayloads().associateBy { it.id }

        assertEquals(
            seeded.map { it.id }.toSet(),
            payloads.keys,
            "everything the group signed here should be in the chronicle"
        )
        seeded.forEach { event ->
            assertEquals(
                event.toJson(),
                assertNotNull(payloads[event.id]).toJson(),
                "kind ${event.kind} did not travel as the group signed it"
            )
        }
    }

    /**
     * The failure the record makes possible, and the allowlist that stops it.
     *
     * A key state is group-signed and verifies perfectly, and the record holds
     * every kind. Without the filter on the way out `ChronicleEvent.build`
     * refuses the page and the room's whole chronicle fails on one event.
     *
     * A room no longer signs its own key state -- a group agrees that before the
     * room exists, in the NIP-17 room its ceremony ran in, and files it under
     * that room -- so this is put on file by hand rather than arriving by
     * itself. That makes it a sharper test than it was, not a hypothetical one:
     * what the filter stops is any group-signed statement *about* the record
     * being replayed as work, and this is one.
     */
    @Test
    fun `a key state the room really signed is never chronicled`() = runBlocking {
        seedSignedWork()

        val keyState = signed(
            EventTemplate<Event>(
                createdAt = 1_700_000_070L,
                kind = GroupKeyStateEvent.KIND,
                tags = GroupKeyStateEvent.assembleTags(
                    chatRoomId = chatRoomId,
                    dkgSessionId = "the-ceremony",
                ),
                content = keyMaterial.thresholdPublicKey.value.toHex(),
            )
        )
        record(keyState)
        assertTrue(
            GroupKeyStateEvent.isSignedByRoom(keyState, chatRoomId),
            "the point of this test is that verification passes"
        )

        val payloads = chronicledPayloads()

        assertTrue(payloads.isNotEmpty(), "the rest of the room's work still chronicles")
        assertEquals(
            TEST_KINDS.all,
            payloads.map { it.kind }.toSet(),
            "a chronicle carries the listed kinds and nothing else"
        )
        assertTrue(payloads.none { it.id == keyState.id })
    }

    /** The same rule, for a kind that is merely not on the list rather than dangerous. */
    @Test
    fun `a signed kind off the list is left behind`() = runBlocking {
        seedSignedWork()

        record(signed(template(GroupWalletStatementEvent.KIND, 1_700_000_080L, "{}")))

        assertEquals(TEST_KINDS.all, chronicledPayloads().map { it.kind }.toSet())
    }

    @Test
    fun `two chronicles of the same rows do not share an id`() = runBlocking {
        seedSignedWork()

        fun idOf(template: EventTemplate<ChronicleEvent>) = ChronicleEvent(
            id = "d".repeat(64),
            pubKey = chatRoomId,
            createdAt = template.createdAt,
            tags = template.tags,
            content = template.content,
            sig = ""
        ).chronicleId()

        // Two members answering one request is the ordinary case, and their page
        // counts differ whenever their databases do. Sharing an id would let one
        // chronicle's pages be counted towards the other's total.
        assertTrue(
            idOf(ChronicleManager.assemble(db, chatRoomId, newMember, kinds = TEST_KINDS).single()) !=
                idOf(ChronicleManager.assemble(db, chatRoomId, newMember, kinds = TEST_KINDS).single())
        )
    }

    /**
     * The default the app's call sites use. A room full of signed work assembles
     * to nothing, because the filter on the way out is the list and the list is
     * empty -- see `ChronicleApplyJvmTest` for the same from the sending side.
     */
    @Test
    fun `under the app's own list a room with signed work assembles nothing`() = runBlocking {
        seedSignedWork()
        assertTrue(db.groupSignedEventDao().getByChatRoomId(chatRoomId).isNotEmpty())

        assertEquals(emptyList(), ChronicleManager.assemble(db, chatRoomId, newMember))
    }
}
