package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip59Giftwrap.rumors.RumorAssembler
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealEvent
import fr.acinq.bitcoin.Crypto
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.nostr.inbox.InboxAnnouncement
import com.machankura.dedun.compose.nostr.inbox.InboxReplyManifest
import com.machankura.dedun.compose.nostr.inbox.InboxSeal
import com.machankura.dedun.compose.nostr.inbox.InboxWire
import com.machankura.dedun.compose.nostr.inbox.InboxWrap
import com.machankura.dedun.compose.text.ProposedEvent
import kotlin.time.Instant

/**
 * The group answering from its inbox (docs/group-inbox.md, Phase 8): a reply is proposed by one
 * admin, read and signed by the quorum, and reaches its recipient as a NIP-17 message from the
 * group; a wrong one is declined out loud; and a member who is not an admin learns nothing of it.
 */
class InboxReplyJvmTest {
    private val harness = GroupInboxHarness(watchers = listOf("dineo"))
    private val zola = KeyPair(Crypto.sha256("zola, who writes to the group".encodeToByteArray()))
    private val zolaKey = zola.pubKey.toHexKey()
    private val zolaEncryption = Crypto.sha256("zola's decoupled key".encodeToByteArray())
    private val zolaEncryptionKey = InboxWire.publicKeyOf(zolaEncryption)
    private val now get() = kotlin.time.Clock.System.now().epochSeconds

    @AfterTest
    fun close() = harness.close()

    private lateinit var alice: GroupInboxHarness.Device
    private lateinit var bob: GroupInboxHarness.Device
    private lateinit var carol: GroupInboxHarness.Device
    private lateinit var dineo: GroupInboxHarness.Device

    /** Three admins with an announced inbox, Zola's message in Alice's, and a member who is not an admin. */
    private suspend fun announced() {
        val devices = listOf("alice", "bob", "carol", "dineo").map { harness.device(it) }
        alice = devices[0]; bob = devices[1]; carol = devices[2]; dineo = devices[3]
        InboxKeyRitualManager.propose(alice.db, alice.room(), alice.publicKey, alice.secret)
        harness.pump()
        val announcement = alice.db.frostSigningSessionDao().getSessionsForChatRoom(harness.roomId).single()
        FrostSigningManager.approve(bob.db, bob.room(), announcement.id)
        harness.pump()

        val e = alice.keys().single().publicKey
        val message = RumorAssembler.assembleRumor<Event>(zolaKey, EventTemplate(now - 60, 14, arrayOf(arrayOf("p", harness.roomId)), "Can I join?"))
        val seal = NostrSignerSync(zola).sign<SealEvent>(now - 60, 13, emptyArray(),
            InboxWire.encrypt(InboxSeal.rumorJson(message), InboxWire.conversationKey(zola.privKey!!, e)))
        assertNotNull(GroupInboxManager.receive(alice.db, InboxWrap.wrap(seal, harness.roomId, e), alice.publicKey))
    }

    private suspend fun conversationId(): String = alice.db.groupInboxDao().getMessages(harness.roomId).first { !it.outgoing }.conversationId

    private suspend fun session(on: GroupInboxHarness.Device, id: String): FrostSigningSession =
        assertNotNull(on.db.frostSigningSessionDao().getSessionById(id))

    /** Zola announces a decoupled key, and Alice's device has heard it. */
    private suspend fun zolaAnnounces(on: GroupInboxHarness.Device) {
        val signed = NostrSignerSync(zola).sign(InboxAnnouncement.keyTemplate(zolaEncryptionKey, now - 3600))
        on.db.nostrEventDao().upsert(
            NostrEvent(id = signed.id, pubKey = signed.pubKey, kind = signed.kind, tags = signed.tags, content = signed.content, sig = signed.sig, createdAt = Instant.fromEpochSeconds(signed.createdAt))
        )
    }

    /** The wrap Alice's device owes Zola, opened with [secret]. */
    private suspend fun zolaOpens(secret: ByteArray, sessionId: String): Event {
        val owed = alice.db.groupInboxDao().getPublicationsFor(sessionId).single { it.recipientPublicKey == zolaKey }
        val wrap = Event.fromJson(owed.eventJson)
        assertEquals(zolaKey, InboxWrap.recipientOf(wrap), "tagged with Zola's identity, as #2361 has it")
        val (_, seal) = assertNotNull(InboxWrap.open(wrap, listOf(secret)), "Zola's key opens the wrap")
        assertTrue(seal.verify(), "the seal is signed")
        assertEquals(harness.roomId, seal.pubKey, "by the group")
        return assertNotNull(InboxSeal.open(seal, secret)).rumor
    }

    @Test
    fun `a reply signed by the quorum reaches Zola from the group`() = runBlocking<Unit> {
        announced()
        val sessionId = InboxReplies.propose(alice.db, alice.room(), alice.publicKey, conversationId(), "Yes, on Thursday.").id
        harness.pump()
        assertTrue(FrostSigningManager.isAwaitingApproval(session(bob, sessionId), bob.db.frostSigningSessionDao().getItems(sessionId)), "Bob is asked")
        FrostSigningManager.approve(bob.db, bob.room(), sessionId)
        harness.pump()
        assertEquals(FrostSigningStage.COMPLETE, session(alice, sessionId).stage)

        val reply = zolaOpens(zola.privKey!!, sessionId)
        assertEquals("Yes, on Thursday.", reply.content)
        assertEquals(harness.roomId, reply.pubKey)
        assertTrue(reply.tags.any { it[0] == "p" && it[1] == zolaKey })

        // Alice's device filed the group's copy at once, in the same conversation.
        val filed = alice.db.groupInboxDao().getConversation(harness.roomId, conversationId())
        assertEquals(listOf(false, true), filed.map { it.outgoing })
        // Only the proposer sends.
        assertEquals(emptyList(), bob.db.groupInboxDao().getPublicationsFor(sessionId))
    }

    @Test
    fun `a reply to someone who decoupled opens with their encryption key`() = runBlocking<Unit> {
        announced()
        zolaAnnounces(alice)
        val sessionId = InboxReplies.propose(alice.db, alice.room(), alice.publicKey, conversationId(), "To your new key").id
        harness.pump()
        FrostSigningManager.approve(bob.db, bob.room(), sessionId)
        harness.pump()
        assertEquals("To your new key", zolaOpens(zolaEncryption, sessionId).content)
    }

    @Test
    fun `the group's copy comes back to every admin as an outgoing message`() = runBlocking<Unit> {
        announced()
        val sessionId = InboxReplies.propose(alice.db, alice.room(), alice.publicKey, conversationId(), "For the record").id
        harness.pump()
        FrostSigningManager.approve(bob.db, bob.room(), sessionId)
        harness.pump()

        val copy = alice.db.groupInboxDao().getPublicationsFor(sessionId).single { it.recipientPublicKey == harness.roomId }
        val filed = assertNotNull(GroupInboxManager.receive(carol.db, Event.fromJson(copy.eventJson), carol.publicKey))
        assertTrue(filed.outgoing)
        assertEquals("For the record", Event.fromJson(filed.rumorJson).content)
        assertNull(GroupInboxManager.receive(dineo.db, Event.fromJson(copy.eventJson), dineo.publicKey), "nothing for a member who is not an admin")
    }

    @Test
    fun `a reply proposed after its relays never took it can be sent again`() = runBlocking<Unit> {
        announced()
        val sessionId = InboxReplies.propose(alice.db, alice.room(), alice.publicKey, conversationId(), "Again").id
        harness.pump()
        FrostSigningManager.approve(bob.db, bob.room(), sessionId)
        harness.pump()
        val first = alice.db.groupInboxDao().getPublicationsFor(sessionId).map { it.eventId }.toSet()
        assertTrue(InboxReplies.sendAgain(alice.db, sessionId))
        val all = alice.db.groupInboxDao().getPublicationsFor(sessionId).map { it.eventId }.toSet()
        assertEquals(4, all.size, "new wraps of the same seals")
        assertTrue(all.containsAll(first))
        assertEquals(1, alice.db.groupInboxDao().getConversation(harness.roomId, conversationId()).count { it.outgoing }, "still one message")
    }

    // ---- what a signer refuses (D12) ---------------------------------------------------------

    /** Proposes [seals] with [manifest] by hand, from Alice, and returns what Bob's device says. */
    private suspend fun bobSays(seals: List<EventTemplate<Event>>, manifest: InboxReplyManifest, sealWith: ByteArray? = null): String? {
        val secret = sealWith ?: alice.keys().first().secretKey!!.hexToByteArray()
        val session = FrostSigningManager.proposeSigningBatch(alice.db, alice.room(), alice.publicKey, events = seals,
            proposalTags = arrayOf(InboxReplyManifest.tag(manifest.seal(secret))))
        harness.pump()
        val onBob = session(bob, session.id)
        if (onBob.signDeclinedAt == null) return null
        return bob.db.marmotInnerEventDao().getByChatRoomAndKinds(harness.roomId, listOf(FrostSigningEvents.DECLINE))
            .firstOrNull { it.publicKey == bob.publicKey }?.content
    }

    private suspend fun built(text: String = "Fine", recipients: List<InboxReplies.Recipient> = listOf(InboxReplies.Recipient(zolaKey, zolaKey, null))): InboxReplies.Built {
        val key = alice.keys().first()
        return InboxReplies.build(harness.roomId, recipients, text, null, key.secretKey!!.hexToByteArray(), key.generation, now = now, sealTime = { now - 30 })
    }

    @Test
    fun `a well-made reply is put to the member, not declined`() = runBlocking<Unit> {
        announced()
        val reply = built()
        assertNull(bobSays(reply.seals, reply.manifest))
    }

    @Test
    fun `a group copy that says something else is declined`() = runBlocking<Unit> {
        announced()
        val reply = built("What Zola reads")
        val other = built("What the admins read")
        val seals = reply.seals.toMutableList().also { it[reply.manifest.groupCopy] = other.seals[other.manifest.groupCopy] }
        assertEquals(InboxRefusal.REPLY_MISMATCH, bobSays(seals, reply.manifest))
    }

    @Test
    fun `a seal to a key the recipient never announced is declined`() = runBlocking<Unit> {
        announced()
        val stranger = InboxWire.publicKeyOf(Crypto.sha256("someone else's key".encodeToByteArray()))
        val reply = built(recipients = listOf(InboxReplies.Recipient(zolaKey, stranger, null)))
        assertEquals(InboxRefusal.REPLY_WRONG_KEY, bobSays(reply.seals, reply.manifest))
    }

    @Test
    fun `a 10044 signed by someone else is declined`() = runBlocking<Unit> {
        announced()
        val forger = KeyPair(Crypto.sha256("a forger".encodeToByteArray()))
        val forged = NostrSignerSync(forger).sign(InboxAnnouncement.keyTemplate(zolaEncryptionKey, now))
        val reply = built(recipients = listOf(InboxReplies.Recipient(zolaKey, zolaEncryptionKey, forged)))
        assertEquals(InboxRefusal.REPLY_WRONG_KEY, bobSays(reply.seals, reply.manifest))
    }

    @Test
    fun `a recipient the reply does not name is declined`() = runBlocking<Unit> {
        announced()
        val yusuf = KeyPair(Crypto.sha256("yusuf".encodeToByteArray())).pubKey.toHexKey()
        val toZola = built()
        val toBoth = built(recipients = listOf(InboxReplies.Recipient(zolaKey, zolaKey, null), InboxReplies.Recipient(yusuf, yusuf, null)))
        // Yusuf's seal holds a rumor naming only Zola: a reply sent to someone it does not address.
        val key = alice.keys().first().secretKey!!.hexToByteArray()
        val yusufIndex = toBoth.manifest.seals.indexOfFirst { it.recipient == yusuf }
        val seals = toBoth.manifest.seals.map { InboxSeal.template(toZola.rumor, key, it.key, now - 30) }
        assertEquals(yusufIndex >= 0, true)
        assertEquals(InboxRefusal.REPLY_MISMATCH, bobSays(seals, toBoth.manifest))
    }

    @Test
    fun `a blank reply is declined`() = runBlocking<Unit> {
        announced()
        val key = alice.keys().first()
        val blank = RumorAssembler.assembleRumor<Event>(harness.roomId, EventTemplate(now, 14, arrayOf(arrayOf("p", zolaKey)), "   "))
        val manifest = built().manifest
        val seals = manifest.seals.map { InboxSeal.template(blank, key.secretKey!!.hexToByteArray(), it.key, now - 30) }
        assertEquals(InboxRefusal.REPLY_BLANK, bobSays(seals, manifest))
    }

    @Test
    fun `a signer whose device holds no inbox key declines, rather than sign what it cannot read`() = runBlocking<Unit> {
        announced()
        bob.db.inboxKeyDao().wipeKeys(harness.roomId, bob.publicKey, kotlin.time.Clock.System.now())
        val reply = built()
        assertEquals(InboxRefusal.REPLY_NO_KEY, bobSays(reply.seals, reply.manifest))
    }

    @Test
    fun `a member who is not an admin learns no text and no recipient`() = runBlocking<Unit> {
        announced()
        val sessionId = InboxReplies.propose(alice.db, alice.room(), alice.publicKey, conversationId(), "Private words").id
        harness.pump()
        val sealed = assertNotNull(alice.db.groupInboxDao().getSigning(sessionId)?.manifest)
        assertNull(InboxReplyManifest.open(sealed, Crypto.sha256("any key dineo could have".encodeToByteArray())))

        val items = alice.db.frostSigningSessionDao().getItems(sessionId).map { Event.fromJson(it.unsignedEventJson) }
        assertEquals("A private reply from the group's inbox", ProposedEvent.summarize(items.first()).label)
        items.forEach { seal ->
            assertTrue("Private words" !in seal.content && zolaKey !in seal.content && seal.tags.none { zolaKey in it })
        }
        // Bob, who holds the key, reads it on the signing screen.
        val notes = InboxRefusal.notes(bob.db, harness.roomId, bob.publicKey, sessionId, items)
        assertEquals("The group would say: Private words", notes.first())
    }
}
