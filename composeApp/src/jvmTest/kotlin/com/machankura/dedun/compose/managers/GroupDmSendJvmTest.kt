package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip44Encryption.Nip44v2
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.ExperimentalFeatures
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.database.repository.DatabaseGroupDmRepository
import com.machankura.dedun.compose.network.dto.RelayDTO
import com.machankura.dedun.compose.network.relays.EventPublishTransport
import com.machankura.dedun.compose.network.relays.NostrPublishResult
import com.machankura.dedun.compose.network.sockets.NostrIncomingMessage
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.nostr.groupdm.AdminSeal
import com.machankura.dedun.compose.nostr.groupdm.EcdhPurpose
import com.machankura.dedun.compose.nostr.groupdm.GroupDmManifest
import com.machankura.dedun.compose.nostr.groupdm.GroupDmSeal
import com.machankura.dedun.compose.text.ProposedEvent
import com.machankura.dedun.compose.ui.view.state.GroupDmOutgoingState

/**
 * Phase 9 of docs/group-threshold-ecdh.md: the group writing to someone. A proposed by A and
 * signed by B reaches the recipient as stock NIP-17, every admin sees it as outgoing, and every
 * way a proposal could say something other than what the signers read is declined.
 */
class GroupDmSendJvmTest {
    private val net = GroupDmNetwork(n = 4, t = 2)
    private val a get() = net.devices[0]
    private val b get() = net.devices[1]
    private val c get() = net.devices[2]
    private val d get() = net.devices[3]

    private val recipient = KeyPair()
    private val other = KeyPair()
    private val r get() = recipient.pubKey.toHexKey()
    private val r2 get() = other.pubKey.toHexKey()

    @BeforeTest
    fun setUp() = runBlocking {
        ExperimentalFeatures.groupDirectMessagesOverride = true
        net.admins = net.members.take(3).toSet()
        net.install()
        net.all()
        net.openMessages()
        // What every admin's device holds of the people it writes to: their profile, and R's 10050.
        listOf(recipient, other).forEach { person ->
            val profile = NostrSignerSync(person).sign<Event>(1_700_000_000, 0, emptyArray(), """{"name":"someone"}""")
            net.devices.forEach { it.db.nostrEventDao().upsert(rowOf(profile)) }
        }
        val inbox = NostrSignerSync(recipient).sign<Event>(1_700_000_000, 10050, arrayOf(arrayOf("relay", "wss://r.example")), "")
        net.devices.forEach { it.db.nostrEventDao().upsert(rowOf(inbox)) }
    }

    @AfterTest
    fun tearDown() {
        net.close()
        ExperimentalFeatures.groupDirectMessagesOverride = null
    }

    private fun rowOf(e: Event) = NostrEvent(id = e.id, pubKey = e.pubKey, kind = e.kind, tags = e.tags, content = e.content, sig = e.sig, createdAt = Instant.fromEpochSeconds(e.createdAt))

    private suspend fun send(to: List<HexKey>, text: String) = GroupDmSender.send(a.db, a.room, a.publicKey, a.privateKey, to, text)

    /** Sends, letting the admins compute any key it waits on first. */
    private suspend fun sendWhenReady(to: List<HexKey>, text: String): String {
        var outcome = send(to, text)
        if (outcome is GroupDmSender.Outcome.Preparing) {
            net.pump()
            outcome = send(to, text)
        }
        return assertIs<GroupDmSender.Outcome.Proposed>(outcome).sessionId
    }

    private class Transport : EventPublishTransport {
        val sent = mutableListOf<Pair<NostrEvent, Set<String>>>()
        override suspend fun publishEvent(nostrEvent: NostrEvent, relays: Set<RelayDTO>): Flow<NostrPublishResult> {
            sent += nostrEvent to relays.map { it.url }.toSet()
            return flowOf(*relays.map { NostrPublishResult(result = NostrIncomingMessage.OkMessage(eventId = nostrEvent.id, success = true), relayUrl = it.url) }.toTypedArray())
        }
    }

    @Test
    fun `a message proposed by A and signed by B reaches the recipient as stock NIP-17`(): Unit = runBlocking {
        assertIs<GroupDmSender.Outcome.Preparing>(send(listOf(r), "hello from the group"), "the admins compute R's key first")
        net.pump()
        val sessionId = sendWhenReady(listOf(r), "hello from the group")
        net.pump()
        FrostSigningManager.approve(b.db, b.room, sessionId)
        net.pump()
        assertEquals(FrostSigningStage.COMPLETE, a.db.frostSigningSessionDao().getSessionById(sessionId)?.stage)

        // The proposer wrapped it, ordinary NIP-59, and owes it to R's relays.
        val owed = a.db.groupDmDao().getOutbound(sessionId).single()
        val wrap = Event.fromJson(assertNotNull(owed.wrapJson)) as GiftWrapEvent
        val opened = assertNotNull(wrap.unwrapAndUnsealOrNull(NostrSignerInternal(recipient)), "a stock client opens it")
        assertEquals("hello from the group", opened.content)
        assertEquals(net.roomId, opened.pubKey)

        val transport = Transport()
        assertTrue(GroupDmPublisher(DatabaseGroupDmRepository(a.db), transport).publish(owed))
        assertTrue("wss://r.example/" in transport.sent.single().second)

        // C did not sign, and sees it as outgoing.
        val onC = assertNotNull(c.db.groupDmDao().getMessageBySigningSession(sessionId))
        assertTrue(onC.outgoing)
        assertEquals("hello from the group", Event.fromJson(onC.rumorJson).content)
        val lineOnA = DatabaseGroupDmRepository(a.db).observeConversation(net.roomId, onC.conversationId).first().single()
        assertEquals(GroupDmOutgoingState.SENT, lineOnA.outgoingState)

        // A recipient written to before needs no round: the next message is proposed at once.
        val roundsBefore = a.db.groupDmDao().getAllOpenRounds().size
        assertIs<GroupDmSender.Outcome.Proposed>(send(listOf(r), "and again"))
        assertEquals(roundsBefore, a.db.groupDmDao().getAllOpenRounds().size)

        // R can answer: the reply opens on the admins' devices like any message to the group.
        val reply = GiftWrapEvent.create(
            com.vitorpamplona.quartz.nip59Giftwrap.seals.SealEvent.create(
                Event("", r, 1_700_000_100, 14, arrayOf(arrayOf("p", net.roomId)), "thanks", ""), net.roomId, NostrSignerInternal(recipient)
            ),
            net.roomId,
        )
        net.receive(reply, a, b)
        net.pump(between = listOf(a, b))
        assertTrue(a.db.groupDmDao().getMessages(net.roomId).any { Event.fromJson(it.rumorJson).content == "thanks" })
    }

    @Test
    fun `send again wraps from any signer's device`(): Unit = runBlocking {
        send(listOf(r), "x"); net.pump()
        val sessionId = sendWhenReady(listOf(r), "x")
        net.pump()
        FrostSigningManager.approve(b.db, b.room, sessionId)
        net.pump()
        val onB = b.db.groupDmDao().getOutbound(sessionId).single()
        assertNull(onB.wrapJson, "only the proposer wraps on its own")
        assertTrue(GroupDmSender.sendAgain(b.db, sessionId))
        val wrap = Event.fromJson(assertNotNull(b.db.groupDmDao().getOutbound(sessionId).single().wrapJson)) as GiftWrapEvent
        assertEquals("x", wrap.unwrapAndUnsealOrNull(NostrSignerInternal(recipient))?.content)
    }

    // --- every refusal on its own

    /** The keys for R and R2, computed honestly first, so a dishonest seal is the only thing wrong. */
    private suspend fun keysReady() {
        send(listOf(r, r2), "warm up"); net.pump()
        listOf(a, b).forEach { device ->
            assertNotNull(device.db.groupDmDao().getConversationKey(net.roomId, r))
            assertNotNull(device.db.groupDmDao().getConversationKey(net.roomId, r2))
        }
    }

    private suspend fun keyOf(recipient: HexKey) = a.db.groupDmDao().getConversationKey(net.roomId, recipient)!!.conversationKey.hexToByteArray()

    private suspend fun evidenceOf(person: KeyPair): Event = NostrSignerSync(person).sign(1_700_000_000, 0, emptyArray(), "{}")

    private val now get() = Clock.System.now().epochSeconds

    private fun rumorTo(vararg to: HexKey, text: String = "the words") =
        GroupDmSeal.rumor(net.roomId, now, 14, to.map { arrayOf("p", it) }.toTypedArray(), text)

    /** A proposal from A, built by hand, sealed to the other admins as A's device would. */
    private suspend fun proposeRaw(seals: List<EventTemplate<Event>>, manifest: GroupDmManifest, slotsFor: List<GroupDmNetwork.Device> = listOf(b, c)): String {
        val sealed = AdminSeal.seal(manifest.encode(), ChillDkgRitualManager.deriveHostSecretKey(a.privateKey), slotsFor.map { net.hostKeys[it.index] })
        return FrostSigningManager.proposeSigningBatch(a.db, a.room, a.publicKey, seals, proposalTags = manifest.toTags(sealed)).id
    }

    private suspend fun assertDeclinedBy(device: GroupDmNetwork.Device, sessionId: String, reason: String = GroupDmRefusal.REASON) {
        net.pump(between = listOf(a, device))
        val session = assertNotNull(device.db.frostSigningSessionDao().getSessionById(sessionId), "filed on $device")
        assertNotNull(session.signDeclinedAt, "declined on $device")
        assertNull(device.db.groupDmDao().getMessageBySigningSession(sessionId), "and not filed as the group's")
        val declines = a.db.frostSigningSessionDao().getMessagesByKind(sessionId, FrostSigningEvents.DECLINE)
        assertTrue(declines.any { it.signerPublicKey == device.publicKey }, "the proposer's device shows the decline")
        val sent = net.outbox(device, listOf(FrostSigningEvents.DECLINE)).map { it.content }
        assertTrue(reason in sent, "said with its reason: $sent")
    }

    private fun manifestOf(vararg people: Pair<HexKey, Event>) = GroupDmManifest(people.map { GroupDmManifest.Seal(it.first, it.second) }, null)

    @Test
    fun `seals that say different things to different people are declined`(): Unit = runBlocking {
        keysReady()
        val one = rumorTo(r, r2, text = "the truth")
        val two = rumorTo(r, r2, text = "something else")
        val id = proposeRaw(
            listOf(GroupDmSeal.build(one, keyOf(r), now - 60), GroupDmSeal.build(two, keyOf(r2), now - 60)),
            manifestOf(r to evidenceOf(recipient), r2 to evidenceOf(other)),
        )
        assertDeclinedBy(b, id)
    }

    @Test
    fun `a seal to the wrong key is declined`(): Unit = runBlocking {
        keysReady()
        val rumor = rumorTo(r)
        val id = proposeRaw(listOf(GroupDmSeal.build(rumor, keyOf(r2), now - 60)), manifestOf(r to evidenceOf(recipient)))
        assertDeclinedBy(b, id)
    }

    @Test
    fun `a message naming a recipient the manifest does not is declined`(): Unit = runBlocking {
        keysReady()
        val rumor = rumorTo(r, r2)
        val id = proposeRaw(listOf(GroupDmSeal.build(rumor, keyOf(r), now - 60)), manifestOf(r to evidenceOf(recipient)))
        assertDeclinedBy(b, id)
    }

    @Test
    fun `forged evidence is declined`(): Unit = runBlocking {
        keysReady()
        val rumor = rumorTo(r)
        val id = proposeRaw(listOf(GroupDmSeal.build(rumor, keyOf(r), now - 60)), manifestOf(r to evidenceOf(KeyPair())))
        assertDeclinedBy(b, id)
    }

    @Test
    fun `a blank message is declined`(): Unit = runBlocking {
        keysReady()
        val rumor = rumorTo(r, text = "   ")
        val id = proposeRaw(listOf(GroupDmSeal.build(rumor, keyOf(r), now - 60)), manifestOf(r to evidenceOf(recipient)))
        assertDeclinedBy(b, id)
    }

    @Test
    fun `a seal with tags is declined`(): Unit = runBlocking {
        keysReady()
        val honest = GroupDmSeal.build(rumorTo(r), keyOf(r), now - 60)
        val tagged = EventTemplate<Event>(honest.createdAt, honest.kind, arrayOf(arrayOf("expiration", "${now + 99}")), honest.content)
        val id = proposeRaw(listOf(tagged), manifestOf(r to evidenceOf(recipient)))
        assertDeclinedBy(b, id)
    }

    @Test
    fun `a signer outside the current admins declines, and reads it only as a private message from the group`(): Unit = runBlocking {
        keysReady()
        val id = proposeRaw(listOf(GroupDmSeal.build(rumorTo(r), keyOf(r), now - 60)), manifestOf(r to evidenceOf(recipient)))
        assertDeclinedBy(d, id, GroupDmRefusal.CANNOT_READ)
        val item = d.db.frostSigningSessionDao().getItems(id).single()
        assertEquals("A private message from the group", ProposedEvent.summarize(Event.fromJson(item.unsignedEventJson)).label)
        assertEquals(emptyList(), d.db.groupDmDao().getMessages(net.roomId), "and learns nothing more")
    }

    @Test
    fun `an honest message is put to the signer, who sees its words`(): Unit = runBlocking {
        keysReady()
        val id = proposeRaw(listOf(GroupDmSeal.build(rumorTo(r, text = "fine"), keyOf(r), now - 60)), manifestOf(r to evidenceOf(recipient)))
        net.pump(between = listOf(a, b))
        val session = assertNotNull(b.db.frostSigningSessionDao().getSessionById(id))
        assertNull(session.signDeclinedAt)
        assertTrue(FrostSigningManager.isAwaitingApproval(session, b.db.frostSigningSessionDao().getItems(id)))
        assertEquals("fine", Event.fromJson(assertNotNull(b.db.groupDmDao().getMessageBySigningSession(id)).rumorJson).content)
    }
}
