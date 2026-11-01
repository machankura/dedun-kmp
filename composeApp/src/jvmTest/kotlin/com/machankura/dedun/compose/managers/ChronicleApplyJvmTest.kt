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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.MarmotKeyPackage
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.nostr.chronicle.ChronicleEvent
import com.machankura.dedun.compose.nostr.chronicle.ChronicleKinds
import com.machankura.dedun.compose.nostr.chronicle.ChronicleRequestEvent
import com.machankura.dedun.compose.nostr.chronicle.TEST_KINDS
import com.machankura.dedun.compose.nostr.chronicle.tags.ChronicleIdTag
import com.machankura.dedun.compose.nostr.chronicle.tags.ChroniclePageTag
import com.machankura.dedun.compose.nostr.chronicle.tags.ChronicleRecipientTag
import com.vitorpamplona.quartz.marmot.mip02Welcome.WelcomeEvent
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent

/**
 * Two devices, two databases: one that did the work and one that arrived after
 * it, with the pages between them carried by hand.
 *
 * This is the claim the whole feature is: a member who holds no share, took part
 * in no signing session and cannot decrypt a word of the room's history ends up
 * holding what the group signed, and gets there without trusting the member who
 * sent it.
 *
 * The negative cases matter more than the positive one. Verification is what
 * makes a chronicle safe to accept from anybody, so what has to be proved is that
 * it refuses -- a forged payload beside honest ones, and a genuine key state
 * replayed out of its epoch, which passes every signature check there is and is
 * stopped by nothing but the allowlist.
 *
 * The app's own list of kinds is empty, so everything here runs on [TEST_KINDS]
 * except the last section, which is about the app's list and holds it to
 * carrying nothing in either direction.
 */
class ChronicleApplyJvmTest {
    /** The member who did the work. */
    private val sender: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    /** The member who arrived afterwards. No share, no sessions, no history. */
    private val receiver: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())

    @AfterTest
    fun closeDbs() {
        sender.close()
        receiver.close()
    }

    private val participants = 3
    private val threshold = 2

    private val keyMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(
            ByteVector32("1c0ffee0000000000000000000000000000000000000000000000000000000a1")
        ),
        nParticipants = participants,
        threshold = threshold
    )

    /** A second group entirely: its own key, its own quorum, its own rooms. */
    private val otherKeyMaterial: KeyMaterial = Frost.trustedDealerKeygen(
        thresholdSecretKey = PrivateKey(
            ByteVector32("2bada550000000000000000000000000000000000000000000000000000000b2")
        ),
        nParticipants = participants,
        threshold = threshold
    )

    private val room: SharedKeyDerivation.Derived = SharedKeyDerivation.derive(
        thresholdPublicKey = keyMaterial.thresholdPublicKey.value.toHex(),
        path = SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH
    )

    private val chatRoomId = room.hex

    private val oldMember = "8".repeat(64)
    private val newMember = "9".repeat(64)

    private fun groupSignature(material: KeyMaterial, eventId: String): String {
        val cache = SharedKeyDerivation.derive(
            thresholdPublicKey = material.thresholdPublicKey.value.toHex(),
            path = SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH
        ).cache
        val message = ByteVector(eventId.hexToByteArray())
        val signerIds = listOf(0, 1)

        val nonces = signerIds.map { signerId ->
            SecretNonce.generate(
                sessionRandom = ByteVector32("a".repeat(63) + "${signerId + 1}"),
                secretShare = material.secretShares[signerId],
                publicShare = material.publicShares[signerId],
                tweakedThresholdPublicKey = cache.tweakedPublicKey,
                message = message,
                extraInput = null
            )
        }

        val signingSession = Session.create(
            aggregatedNonce = IndividualNonce.aggregate(nonces.map { it.second }).right!!,
            signerIds = signerIds.map { it.toUInt() },
            signerPublicShares = signerIds.map { material.publicShares[it] },
            nParticipants = participants,
            threshold = threshold,
            tweakCache = cache,
            message = message
        )

        val partials = signerIds.mapIndexed { position, signerId ->
            signingSession.sign(
                nonces[position].first,
                material.secretShares[signerId],
                signerId.toUInt()
            ).right!!
        }

        return signingSession.aggregateSigs(partials).right!!.toByteArray().toHex()
    }

    /** A template as a finished session leaves it, authored by [material]'s room. */
    private fun signedBy(material: KeyMaterial, template: EventTemplate<*>): Event {
        val author = SharedKeyDerivation.derive(
            thresholdPublicKey = material.thresholdPublicKey.value.toHex(),
            path = SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH
        ).hex

        val id = EventHasher.hashId(
            pubKey = author,
            createdAt = template.createdAt,
            kind = template.kind,
            tags = template.tags,
            content = template.content
        )

        return Event(
            id = id,
            pubKey = author,
            createdAt = template.createdAt,
            kind = template.kind,
            tags = template.tags,
            content = template.content,
            sig = groupSignature(material, id)
        )
    }

    private fun signed(template: EventTemplate<*>) = signedBy(keyMaterial, template)

    private fun template(kind: Int, createdAt: Long, content: String, tags: Array<Array<String>> = emptyArray()) =
        EventTemplate<Event>(createdAt = createdAt, kind = kind, tags = tags, content = content)

    private suspend fun seedRoom(db: DedunDatabase, user: String) {
        val nostrEventId = "c".repeat(63) + if (user == newMember) "1" else "2"
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
        db.profileDao().upsert(Profile(publicKey = user, userName = "member", nostrEventId = nostrEventId))
        db.chatRoomDao().upsert(
            ChatRoom(
                id = chatRoomId,
                userPublicKey = user,
                subject = "#Ekklesia",
                description = null,
                mlsGroupState = null,
            )
        )
    }

    /**
     * What `FrostSigningManager.complete` does with a finished batch: file the
     * events as the group signed them, then apply them.
     */
    private suspend fun recordAndApply(db: DedunDatabase, event: Event) {
        db.groupSignedEventDao().record(
            GroupSignedEvent.fromEvent(
                event = event,
                chatRoomId = chatRoomId,
                derivationPath = SharedKeyDerivation.formatPath(),
                frostSigningSessionId = "session-1",
            )
        )
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
    }

    /** The sender's room, holding one signed event of every test kind and two of one. */
    private suspend fun seedSenderWork() {
        seedRoom(sender, oldMember)

        listOf(
            template(
                AdvertisedRelayListEvent.KIND, 1_700_000_000L, "",
                tags = arrayOf(arrayOf("r", "wss://relay.example.com")),
            ),
            template(MetadataEvent.KIND, 1_700_000_010L, "{\"name\":\"Ekklesia\"}"),
            template(TextNoteEvent.KIND, 1_700_000_020L, "The group says hello."),
            template(TextNoteEvent.KIND, 1_700_000_021L, "And says it again."),
        ).forEach { recordAndApply(sender, signed(it)) }
    }

    /**
     * A chronicle page as it reaches a device: stored as an inner event first,
     * exactly as the inbound path stores every payload it decrypts before
     * dispatching on kind, then handed to the manager.
     */
    private suspend fun deliver(
        template: EventTemplate<ChronicleEvent>,
        to: String = newMember,
        from: String = oldMember,
        kinds: ChronicleKinds = TEST_KINDS,
    ): ChronicleManager.Outcome {
        val page = Event(
            id = EventHasher.hashId(
                pubKey = from,
                createdAt = template.createdAt,
                kind = template.kind,
                tags = template.tags,
                content = template.content
            ),
            pubKey = from,
            createdAt = template.createdAt,
            kind = template.kind,
            tags = template.tags,
            // A chronicle page is a member's own rumor. What carries the group's
            // word is each payload's signature, not the envelope's.
            content = template.content,
            sig = ""
        )

        receiver.marmotInnerEventDao().upsert(
            MarmotInnerEvent(
                id = page.id,
                publicKey = page.pubKey,
                kind = page.kind,
                createdAt = Instant.fromEpochSeconds(page.createdAt),
                tags = page.tags,
                content = page.content,
                chatRoomId = chatRoomId,
            )
        )

        return ChronicleManager.receive(receiver, chatRoomId, to, page, kinds)
    }

    private suspend fun senderChronicle() =
        ChronicleManager.assemble(sender, chatRoomId, newMember, kinds = TEST_KINDS)

    /** Every payload of the sender's chronicle, one page each, numbered as [chronicleId]. */
    private suspend fun onePagePerPayload(chronicleId: String = "e".repeat(64)): List<EventTemplate<ChronicleEvent>> {
        val payloads = senderChronicle()
            .flatMap { assertNotNull(ChronicleEvent.decodePage(it.content)) }

        return payloads.mapIndexed { index, payload ->
            ChronicleEvent.build(
                payloads = listOf(payload),
                chronicleId = chronicleId,
                index = index,
                count = payloads.size,
                recipient = newMember,
                createdAt = 1_700_000_100L + index,
                kinds = TEST_KINDS,
            )
        }
    }

    /**
     * Every group-signed event on file as (id, author, signature), sorted.
     *
     * None of the test kinds' arms writes a row of its own, so this table is the
     * whole of what a chronicle restores. Compared rather than counted: two
     * databases can hold the same number of events and disagree about every one,
     * and a chronicle that re-authored each as whoever sent it would pass a count
     * and fail the only thing it is for.
     */
    private suspend fun fingerprints(db: DedunDatabase): List<Triple<String, String, String>> =
        db.groupSignedEventDao().getByChatRoomId(chatRoomId)
            .map { Triple(it.id, it.publicKey, it.signature) }
            .sortedBy { it.first }

    private suspend fun lines(db: DedunDatabase) =
        db.chatMessageDao().getChatMessagesByChatRoomId(chatRoomId).map { it.chatMessage.messageType }

    @Test
    fun `a member who was never there ends up holding what the group signed`() = runBlocking {
        seedSenderWork()
        seedRoom(receiver, newMember)

        assertEquals(
            emptyList(),
            fingerprints(receiver),
            "the new member starts with nothing, which is the whole problem"
        )

        senderChronicle().forEach { deliver(it) }

        val fingerprints = fingerprints(receiver)
        assertTrue(fingerprints.isNotEmpty())

        // Not merely the same number: the same events, each with the group's own
        // signature on it.
        assertEquals(fingerprints(sender), fingerprints)

        fingerprints.forEach { (id, author, signature) ->
            assertEquals(chatRoomId, author, "event $id is not authored by the room")
            assertTrue(signature.isNotBlank(), "event $id came across without a signature")
        }
    }

    /**
     * The member who was handed history can hand it on.
     *
     * The events are the thing itself, and until they are kept the recipient of
     * a chronicle is a dead end -- holding the work, unable to prove any of it,
     * and unable to build a page for the next member to arrive.
     */
    @Test
    fun `the chronicle leaves the receiver holding the events as the group signed them`() = runBlocking {
        seedSenderWork()
        seedRoom(receiver, newMember)

        senderChronicle().forEach { deliver(it) }

        val held = receiver.groupSignedEventDao().getByChatRoomId(chatRoomId)
        val original = sender.groupSignedEventDao().getByChatRoomId(chatRoomId).associateBy { it.id }

        assertEquals(original.keys, held.map { it.id }.toSet())

        held.forEach { event ->
            assertTrue(event.verifies(), "${event.id} does not read back as the room's")
            assertEquals(
                assertNotNull(original[event.id]).toEvent().toJson(),
                event.toEvent().toJson(),
                "${event.id} did not arrive as the group signed it"
            )
            // Nothing on this device ran a session for any of it.
            assertNull(event.frostSigningSessionId)
        }
    }

    /**
     * Every test kind's arm in `applyInnerEvent` hands back a line -- "the group
     * posted in its own name" and the like -- so this is a real refusal and not
     * an absence.
     */
    @Test
    fun `a chronicle files no chat lines`() = runBlocking {
        seedSenderWork()
        seedRoom(receiver, newMember)

        senderChronicle().forEach { deliver(it) }

        assertEquals(
            emptyList(),
            lines(receiver).filterNot { it in ChatMessage.CHRONICLE_TYPES },
            "the chronicle restores the work; the conversation is forward secret and stays gone"
        )
    }

    @Test
    fun `pages arriving out of order still converge`() = runBlocking {
        seedSenderWork()
        seedRoom(receiver, newMember)

        // One page per payload, delivered backwards. Relays give no ordering
        // guarantee, so this is the ordinary case rather than the adversarial
        // one. Nothing in the test list hangs off anything else, so no payload
        // has to wait here; the retry that waiting needs is the next test's.
        val outcomes = onePagePerPayload().reversed().map { deliver(it) }

        assertEquals(0, outcomes.sumOf { it.failed })
        assertEquals(fingerprints(sender), fingerprints(receiver))
    }

    /**
     * The sweep's reason to exist: a payload that threw is run again when the
     * next page arrives, because the pages are on disk and the sweep takes all of
     * them.
     *
     * In the app the missing dependency is another payload's row; nothing in the
     * test list depends on another, so here it is the room row itself, the one
     * foreign key every recorded event has. What is under test is the retry, not
     * the reason for it.
     */
    @Test
    fun `a payload that could not apply yet is retried when the next page arrives`() = runBlocking {
        seedSenderWork()

        val pages = onePagePerPayload()
        assertTrue(pages.size >= 2, "this needs a second page to arrive after the first fails")

        // No room on file yet: the first page's payload has nowhere to go.
        val first = deliver(pages.first())
        assertEquals(1, first.failed, "the first payload should have been left waiting")
        assertEquals(0, first.applied)
        assertEquals(emptyList(), fingerprints(receiver))

        // The room appears, and the next page's sweep takes the first one with it.
        seedRoom(receiver, newMember)
        val second = deliver(pages[1])

        assertEquals(0, second.failed)
        assertEquals(2, second.applied, "the waiting payload and the new one")

        pages.drop(2).forEach { deliver(it) }
        assertEquals(fingerprints(sender), fingerprints(receiver))
    }

    @Test
    fun `one forged payload costs itself and nothing else`() = runBlocking {
        seedRoom(receiver, newMember)

        val honest = signed(template(MetadataEvent.KIND, 1_700_000_000L, "{\"name\":\"Ekklesia\"}"))

        // Four ways to be a dishonest member, in one page beside one honest
        // profile. Every one of them is well-formed and of a kind the list admits.
        val forgeries = listOf(
            // The room's id as author -- everybody knows it -- and a made-up
            // signature.
            Event(
                id = EventHasher.hashId(
                    pubKey = chatRoomId, createdAt = 1_700_000_001L, kind = MetadataEvent.KIND,
                    tags = honest.tags, content = "{\"name\":\"forged\"}"
                ),
                pubKey = chatRoomId, createdAt = 1_700_000_001L, kind = MetadataEvent.KIND,
                tags = honest.tags, content = "{\"name\":\"forged\"}", sig = "9".repeat(128)
            ),
            // A real quorum, of another group.
            signedBy(
                otherKeyMaterial,
                template(MetadataEvent.KIND, 1_700_000_002L, "{\"name\":\"Elsewhere\"}")
            ),
            // Edited after the group signed it.
            Event(
                id = honest.id, pubKey = honest.pubKey, createdAt = honest.createdAt,
                kind = honest.kind,
                tags = arrayOf(arrayOf("name", "tampered")),
                content = honest.content, sig = honest.sig
            ),
            // A member's own rumor, which is what everything on the wire looks
            // like today.
            Event(
                id = EventHasher.hashId(
                    pubKey = oldMember, createdAt = 1_700_000_003L, kind = MetadataEvent.KIND,
                    tags = honest.tags, content = "{\"name\":\"unsigned\"}"
                ),
                pubKey = oldMember, createdAt = 1_700_000_003L, kind = MetadataEvent.KIND,
                tags = honest.tags, content = "{\"name\":\"unsigned\"}", sig = ""
            ),
        )

        val outcome = deliver(
            ChronicleEvent.build(
                payloads = forgeries + honest,
                chronicleId = "f".repeat(64),
                index = 0,
                count = 1,
                recipient = newMember,
                createdAt = 1_700_000_100L,
                kinds = TEST_KINDS,
            )
        )

        assertEquals(4, outcome.skipped)
        assertEquals(1, outcome.applied)

        // Nothing forged was kept. The record is what a later chronicle is built
        // from, so a forgery filed here would be one this member goes on to hand
        // to everybody else.
        assertEquals(
            listOf(honest.id),
            receiver.groupSignedEventDao().getByChatRoomId(chatRoomId).map { it.id },
            "exactly the honest one"
        )
    }

    @Test
    fun `a key state the room really signed cannot be replayed through a chronicle`() = runBlocking {
        seedRoom(receiver, newMember)

        // Signed by the room, verifying perfectly, and refused: the allowlist is
        // the only thing between "the group signed this once" and "any member may
        // re-point what this room signs with, at any time, forever".
        val keyState = signed(
            EventTemplate<Event>(
                createdAt = 1_700_000_060L,
                kind = GroupKeyStateEvent.KIND,
                tags = GroupKeyStateEvent.assembleTags(
                    chatRoomId = chatRoomId,
                    dkgSessionId = "somebody-elses-ceremony",
                ),
                content = keyMaterial.thresholdPublicKey.value.toHex(),
            )
        )
        assertTrue(
            GroupKeyStateEvent.isSignedByRoom(keyState, chatRoomId),
            "the point of this test is that verification passes"
        )

        // Built by hand: ChronicleEvent.build refuses this kind, which is the
        // outbound half of the same rule. Only a hand-rolled page gets this far.
        val page = EventTemplate<ChronicleEvent>(
            createdAt = 1_700_000_100L,
            kind = ChronicleEvent.KIND,
            tags = arrayOf(
                arrayOf("alt", ChronicleEvent.ALT_DESCRIPTION),
                ChronicleIdTag.assemble("a".repeat(64)),
                ChroniclePageTag.assemble(0, 1),
                ChronicleRecipientTag.assemble(newMember),
            ),
            content = ChronicleEvent.encodePage(listOf(keyState)),
        )

        val outcome = deliver(page)

        assertEquals(1, outcome.skipped)
        assertEquals(0, outcome.applied)
        assertNull(receiver.groupKeyStateDao().getByChatRoomId(chatRoomId))
        assertEquals(emptyList(), fingerprints(receiver))
    }

    @Test
    fun `a member the chronicle is not addressed to applies none of it`() = runBlocking {
        seedSenderWork()
        seedRoom(receiver, newMember)

        val outcome = senderChronicle().map { deliver(it, to = "7".repeat(64)) }

        assertEquals(0, outcome.sumOf { it.applied })
        assertEquals(
            emptyList(),
            fingerprints(receiver),
            "a bystander already holds the work; re-applying would only rewrite its provenance"
        )
    }

    @Test
    fun `applying the same chronicle twice changes nothing the second time`() = runBlocking {
        seedSenderWork()
        seedRoom(receiver, newMember)

        val chronicle = senderChronicle()
        chronicle.forEach { deliver(it) }
        val afterFirst = fingerprints(receiver)

        chronicle.forEach { deliver(it) }

        assertEquals(afterFirst, fingerprints(receiver))
        assertEquals(emptyList(), lines(receiver).filterNot { it in ChatMessage.CHRONICLE_TYPES })
    }

    // ---- Asking for one --------------------------------------------------

    private suspend fun queued(db: DedunDatabase, kind: Int) = db.marmotInnerEventDao()
        .getByChatRoomAndKinds(chatRoomId, listOf(kind))
        .filter { it.marmotGroupEventId == null }

    @Test
    fun `a device holding none of the room's work asks for it`() = runBlocking {
        seedRoom(receiver, newMember)

        assertTrue(ChronicleManager.requestIfEmpty(receiver, chatRoomId, newMember, kinds = TEST_KINDS))

        // Queued with no chat line, the way a signing message travels. Broadcast
        // does not depend on one, and a row of envelopes in the transcript is not
        // what a chronicle should leave behind.
        assertEquals(1, queued(receiver, ChronicleRequestEvent.KIND).size)

        // One line saying so, and nothing else. Asking is a thing this device did
        // and the room would otherwise sit empty with no explanation.
        assertEquals(listOf(ChatMessage.TYPE_CHRONICLE_REQUESTED), lines(receiver))
    }

    @Test
    fun `a device does not ask twice while an answer is in flight`() = runBlocking {
        seedRoom(receiver, newMember)

        assertTrue(ChronicleManager.requestIfEmpty(receiver, chatRoomId, newMember, kinds = TEST_KINDS))
        assertFalse(ChronicleManager.requestIfEmpty(receiver, chatRoomId, newMember, kinds = TEST_KINDS))

        assertEquals(1, queued(receiver, ChronicleRequestEvent.KIND).size)
    }

    @Test
    fun `a device holding the work does not ask for it`() = runBlocking {
        seedSenderWork()

        assertFalse(ChronicleManager.requestIfEmpty(sender, chatRoomId, oldMember, kinds = TEST_KINDS))
        assertEquals(0, queued(sender, ChronicleRequestEvent.KIND).size)
    }

    /**
     * "Holds the work" means holds something a chronicle would carry. A device
     * whose only signed event is one the list refuses is as far behind as one
     * holding nothing, and has to ask like one.
     */
    @Test
    fun `a device holding only signed kinds off the list still asks`() = runBlocking {
        seedRoom(receiver, newMember)

        receiver.groupSignedEventDao().record(
            GroupSignedEvent.fromEvent(
                event = signed(
                    EventTemplate<Event>(
                        createdAt = 1_700_000_060L,
                        kind = GroupKeyStateEvent.KIND,
                        tags = GroupKeyStateEvent.assembleTags(
                            chatRoomId = chatRoomId,
                            dkgSessionId = "the-ceremony",
                        ),
                        content = keyMaterial.thresholdPublicKey.value.toHex(),
                    )
                ),
                chatRoomId = chatRoomId,
            )
        )

        assertTrue(ChronicleManager.requestIfEmpty(receiver, chatRoomId, newMember, kinds = TEST_KINDS))
    }

    @Test
    fun `an answered request lets the room ask again`() = runBlocking {
        seedSenderWork()
        seedRoom(receiver, newMember)

        ChronicleManager.requestIfEmpty(receiver, chatRoomId, newMember, kinds = TEST_KINDS)
        assertNotNull(receiver.chatRoomDao().findChatRoomById(chatRoomId)?.chatRoom?.chronicleRequestedAt)

        senderChronicle().forEach { deliver(it) }

        // Cleared on anything applied rather than on the sender claiming the
        // chronicle was complete. A page count is the sender's word about the
        // transfer, not about the group's record, so a member who left work out
        // must not get the last word on whether to ask again.
        assertNull(receiver.chatRoomDao().findChatRoomById(chatRoomId)?.chatRoom?.chronicleRequestedAt)
    }

    @Test
    fun `answering a request queues the chronicle for whoever asked`() = runBlocking {
        seedSenderWork()

        val pages = ChronicleManager.sendTo(sender, chatRoomId, oldMember, recipient = newMember, kinds = TEST_KINDS)

        assertEquals(1, pages)

        val queuedPage = queued(sender, ChronicleEvent.KIND).single()
        assertEquals(oldMember, queuedPage.publicKey, "the page is the answering member's own rumor")

        val page = ChronicleEvent(
            id = queuedPage.id,
            pubKey = queuedPage.publicKey,
            createdAt = queuedPage.createdAt.epochSeconds,
            tags = queuedPage.tags,
            content = queuedPage.content,
            sig = "",
        )
        assertEquals(newMember, page.recipient())
        assertEquals(TEST_KINDS.all, page.payloads()?.map { it.kind }?.toSet())

        // One line about the chronicle, and no line per page.
        assertEquals(listOf(ChatMessage.TYPE_CHRONICLE_SENT), lines(sender))
    }

    @Test
    fun `a member with nothing signed answers nothing rather than an empty chronicle`() = runBlocking {
        seedRoom(receiver, newMember)

        assertEquals(
            0,
            ChronicleManager.sendTo(receiver, chatRoomId, newMember, recipient = oldMember, kinds = TEST_KINDS)
        )
        assertEquals(0, queued(receiver, ChronicleEvent.KIND).size)
    }

    @Test
    fun `a member does not answer their own request`() = runBlocking {
        seedSenderWork()

        assertEquals(
            0,
            ChronicleManager.sendTo(sender, chatRoomId, oldMember, recipient = oldMember, kinds = TEST_KINDS)
        )
        assertEquals(0, queued(sender, ChronicleEvent.KIND).size)
    }

    @Test
    fun `asking and answering completes the round trip`() = runBlocking {
        seedSenderWork()
        seedRoom(receiver, newMember)

        // The joiner asks.
        assertTrue(ChronicleManager.requestIfEmpty(receiver, chatRoomId, newMember, kinds = TEST_KINDS))

        // A member holding the work answers it, addressed to whoever asked.
        ChronicleManager.sendTo(sender, chatRoomId, oldMember, recipient = newMember, kinds = TEST_KINDS)

        // The pages reach the joiner, who applies them.
        queued(sender, ChronicleEvent.KIND).forEach { queuedPage ->
            deliver(
                EventTemplate(
                    createdAt = queuedPage.createdAt.epochSeconds,
                    kind = queuedPage.kind,
                    tags = queuedPage.tags,
                    content = queuedPage.content,
                )
            )
        }

        assertEquals(fingerprints(sender), fingerprints(receiver))
        assertNull(receiver.chatRoomDao().findChatRoomById(chatRoomId)?.chatRoom?.chronicleRequestedAt)
    }

    // ---- The push behind a Welcome ---------------------------------------

    /**
     * The invitee's key package, which is how `deliveryWelcome` learns who it is
     * addressing. The bytes are never decoded on this path.
     */
    private suspend fun seedInviteeKeyPackage(): String {
        val keyPackageId = "1".repeat(64)
        val nostrEventId = "2".repeat(64)

        sender.nostrEventDao().upsert(
            NostrEvent(
                id = nostrEventId,
                pubKey = newMember,
                kind = 0,
                tags = emptyArray(),
                content = "{}",
                sig = "0".repeat(128),
            )
        )
        sender.profileDao().upsert(
            Profile(publicKey = newMember, userName = "invitee", nostrEventId = nostrEventId)
        )
        sender.marmotKeyPackageDao().upsert(
            MarmotKeyPackage(
                id = keyPackageId,
                publicKey = newMember,
                tlsEncodedMarmotKeyPackage = ByteArray(0),
            )
        )

        return keyPackageId
    }

    private suspend fun invite() = sender.marmotOutboundDao().deliveryWelcome(
        chatRoomId = chatRoomId,
        userPublicKey = oldMember,
        welcomeBytes = ByteArray(8),
        peerKeyPackageEventId = seedInviteeKeyPackage(),
        relays = listOf("wss://relay.example.com"),
        createdAt = Clock.System.now(),
    )

    private suspend fun welcomesSent() = sender.giftWrapPayloadDao()
        .getByChatRoomAndKinds(chatRoomId, listOf(WelcomeEvent.KIND))
        .size

    @Test
    fun `inviting into a room with nothing signed offers nothing, and still invites`() = runBlocking {
        seedRoom(sender, oldMember)

        invite()

        assertEquals(0, queued(sender, ChronicleEvent.KIND).size)
        // The Welcome itself still went out. A push that has nothing to say is
        // not a failed invite, and nothing about the chronicle may report one:
        // the invitee asks for themselves on their first open regardless.
        assertEquals(1, welcomesSent())
    }

    @Test
    fun `an answered catch-up leaves one line, whatever it took to deliver`() = runBlocking {
        seedSenderWork()
        seedRoom(receiver, newMember)

        ChronicleManager.requestIfEmpty(receiver, chatRoomId, newMember, kinds = TEST_KINDS)

        // Delivered a page at a time and out of order, so the sweep runs several
        // times over several pages. The transcript is not a log of that.
        onePagePerPayload().reversed().forEach { deliver(it) }

        // Newest first, which is the transcript's order: the answer is the line above
        // the composer and the ask sits over it. Both land in the same second, so what
        // decides between them is `ChatMessageDao`'s `id DESC` rather than the sorter --
        // without it this read whichever way SQLite happened to hand the tie back.
        assertEquals(
            listOf(ChatMessage.TYPE_CHRONICLE_RECEIVED, ChatMessage.TYPE_CHRONICLE_REQUESTED),
            lines(receiver)
        )
    }

    @Test
    fun `a push nobody asked for says nothing in the transcript`() = runBlocking {
        seedSenderWork()
        seedRoom(receiver, newMember)

        // The invite-time push lands before the member has opened the room, so
        // "caught up on work you have not seen yet" is a line about nothing.
        senderChronicle().forEach { deliver(it) }

        assertEquals(emptyList(), lines(receiver))
    }

    // ---- The app's own list ----------------------------------------------
    //
    // Everything above names its kinds. The app's call sites do not, so these hold
    // the default -- ChronicleEvent.KINDS -- to what its comment promises: with
    // nothing on the list, a device asks for nothing, sends nothing, and applies
    // nothing an older build sends it.

    /**
     * The decision itself, pinned. Adding a kind back is meant to be made on
     * purpose, with the reason it is safe to replay, and this is the line that
     * has to change when it is.
     */
    @Test
    fun `the app's own list carries nothing`() {
        assertTrue(ChronicleEvent.KINDS.isEmpty())
        assertTrue(ChronicleEvent.CHRONICLABLE_KINDS.isEmpty())
    }

    /**
     * Asking while nothing could answer would put a request on the wire and a
     * line in the transcript on every room a member opens, for a reply that can
     * only ever be silence.
     */
    @Test
    fun `under the app's own list a device asks for nothing`() = runBlocking {
        seedRoom(receiver, newMember)

        assertFalse(ChronicleManager.requestIfEmpty(receiver, chatRoomId, newMember))

        assertEquals(0, queued(receiver, ChronicleRequestEvent.KIND).size)
        assertNull(
            receiver.chatRoomDao().findChatRoomById(chatRoomId)?.chatRoom?.chronicleRequestedAt,
            "a stamp would hold back a real request on the day the list grows"
        )
        assertEquals(emptyList(), lines(receiver))
    }

    /**
     * Both ways a chronicle goes out -- the reply to a request and the push
     * behind a Welcome -- from a room that holds plenty the group signed. The
     * table holds every kind the group ever signed, so this is the filter on the
     * way out doing its job, not an empty room.
     */
    @Test
    fun `under the app's own list a device sends nothing, even holding signed work`() = runBlocking {
        seedSenderWork()
        assertTrue(sender.groupSignedEventDao().getByChatRoomId(chatRoomId).isNotEmpty())

        // Answering a request.
        assertEquals(0, ChronicleManager.sendTo(sender, chatRoomId, oldMember, recipient = newMember))

        // The push behind a Welcome, which calls the same thing with no kinds.
        invite()

        assertEquals(0, queued(sender, ChronicleEvent.KIND).size)
        assertEquals(emptyList(), lines(sender).filter { it in ChatMessage.CHRONICLE_TYPES })
        // And the invite itself still went out.
        assertEquals(1, welcomesSent())
    }

    /**
     * A member on an older build still chronicles, and its pages are genuine:
     * events the room really signed, of kinds that build still lists. Arriving
     * here they are refused by the list, not by the signature -- every payload
     * counts as skipped, none as failed, so nothing waits to be retried.
     */
    @Test
    fun `under the app's own list a page from an older build applies nothing`() = runBlocking {
        seedSenderWork()
        seedRoom(receiver, newMember)

        val pages = senderChronicle()
        val payloadCount = pages.sumOf { assertNotNull(ChronicleEvent.decodePage(it.content)).size }

        pages.forEach { deliver(it, kinds = ChronicleEvent.KINDS) }

        // And a sweep of everything stored, the way the next arrival would run it.
        val outcome = ChronicleManager.sweep(receiver, chatRoomId, newMember)

        assertEquals(0, outcome.applied)
        assertEquals(0, outcome.failed)
        assertEquals(payloadCount, outcome.skipped)

        assertEquals(emptyList(), fingerprints(receiver))
        assertEquals(emptyList(), lines(receiver))
    }
}
