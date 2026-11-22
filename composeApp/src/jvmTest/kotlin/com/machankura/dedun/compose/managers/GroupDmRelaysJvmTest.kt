package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.database.repository.DatabaseGroupDmRepository
import com.machankura.dedun.compose.network.dto.RelayDTO
import com.machankura.dedun.compose.network.relays.EventPublishTransport
import com.machankura.dedun.compose.network.relays.NostrPublishResult
import com.machankura.dedun.compose.network.sockets.NostrIncomingMessage
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.text.ProposedEvent

/**
 * Phase 6 of docs/group-threshold-ecdh.md: the group's decision to receive direct messages, a
 * kind 10050 it signs, published unchanged by the proposer's device, and refused when it lists
 * a relay nobody can reach.
 */
class GroupDmRelaysJvmTest {
    private val net = GroupDmNetwork(n = 3, t = 2)

    @AfterTest
    fun tearDown() = net.close()

    private class Transport : EventPublishTransport {
        val sent = mutableListOf<Pair<NostrEvent, Set<String>>>()
        override suspend fun publishEvent(nostrEvent: NostrEvent, relays: Set<RelayDTO>): Flow<NostrPublishResult> {
            sent += nostrEvent to relays.map { it.url }.toSet()
            return flowOf(*relays.map { NostrPublishResult(result = NostrIncomingMessage.OkMessage(eventId = nostrEvent.id, success = true), relayUrl = it.url) }.toTypedArray())
        }
    }

    private suspend fun signRelays(relays: List<String>): String {
        val a = net.devices[0]
        val b = net.devices[1]
        val session = GroupDmRelays.propose(a.db, a.room, a.publicKey, relays)
        net.pump()
        FrostSigningManager.approve(b.db, b.room, session.id)
        net.pump()
        assertEquals(FrostSigningStage.COMPLETE, a.db.frostSigningSessionDao().getSessionById(session.id)?.stage)
        return session.id
    }

    @Test
    fun `a signed 10050 is published unchanged by its proposer, and writes no NostrEvent row`(): Unit = runBlocking {
        net.install()
        net.all()
        val a = net.devices[0]
        val sessionId = signRelays(listOf("wss://dm.example.com/", "wss://inbox.example.org"))

        val owed = a.db.groupDmDao().getOutbound(sessionId).single()
        val signed = Event.fromJson(assertNotNull(owed.wrapJson))
        assertEquals(GroupDmRelays.KIND, signed.kind)
        assertEquals(net.roomId, signed.pubKey)
        assertTrue(signed.verify(), "the group's own signature")
        assertEquals(listOf("wss://dm.example.com/", "wss://inbox.example.org/"), GroupDmRelays.relaysIn(signed.tags))
        // Only the proposer owes it.
        assertEquals(emptyList(), net.devices[1].db.groupDmDao().getOutbound(sessionId))

        val transport = Transport()
        assertTrue(GroupDmPublisher(DatabaseGroupDmRepository(a.db), transport).publish(owed))
        val (sent, to) = transport.sent.single()
        assertEquals(signed.id, sent.id)
        assertEquals(signed.sig, sent.sig)
        assertTrue("wss://dm.example.com/" in to && "wss://inbox.example.org/" in to)
        assertNotNull(a.db.groupDmDao().getOutbound(sessionId).single().publishedAt)

        net.devices.forEach { device ->
            assertNull(device.db.nostrEventDao().getNostrEventById(signed.id), "no NostrEvent row on $device")
            assertEquals(listOf("wss://dm.example.com/", "wss://inbox.example.org/"), GroupDmRelays.relaysOf(device.db, net.roomId))
        }
    }

    @Test
    fun `the transcript says the group can receive messages, and then that it changed where`(): Unit = runBlocking {
        net.install()
        net.all()
        signRelays(listOf("wss://dm.example.com"))
        Thread.sleep(1100) // a second 10050 needs a later created_at to be the newer list
        signRelays(listOf("wss://other.example.com"))

        net.devices.forEach { device ->
            val lines = device.db.chatMessageDao().getChatMessagesByChatRoomId(net.roomId)
                .map { it.chatMessage }
                .filter { it.messageType == ChatMessage.TYPE_GROUP_DM_RELAYS_SIGNED }
                .sortedBy { it.createdAt }
                .map { it.content }
            assertEquals(listOf("The group can now receive messages", "The group changed where it receives messages"), lines, "$device")
            assertEquals(listOf("wss://other.example.com/"), GroupDmRelays.relaysOf(device.db, net.roomId))
        }
    }

    // --- the refusal, on the proposal harness

    private val harness = SigningProposalHarness()

    @AfterTest
    fun closeHarness() {
        RoomAdmins.override = null
        harness.close()
    }

    private fun proposeRelayList(relays: List<String>) = runBlocking {
        harness.propose(harness.members[0]) { db, room ->
            FrostSigningManager.proposeSigning(db, room, harness.members[0], kind = GroupDmRelays.KIND, tags = relays.map { arrayOf("relay", it) }.toTypedArray(), content = "")
        }
    }

    @Test
    fun `a 10050 listing a relay that is not public wss is declined with its reason`(): Unit = runBlocking {
        val sent = proposeRelayList(listOf("wss://fine.example.com", "ws://plain.example.com"))
        harness.deliver(sent, harness.members[1])
        val session = assertNotNull(harness.db.frostSigningSessionDao().getSessionById(sent.session.id))
        assertNotNull(session.signDeclinedAt)
        assertEquals(listOf(GroupDmRelayRefusal.REASON), harness.queued(FrostSigningEvents.DECLINE).map { it.content })

        val localhost = proposeRelayList(listOf("wss://localhost:7777"))
        harness.deliver(localhost, harness.members[1])
        assertEquals(listOf(GroupDmRelayRefusal.REASON), harness.queued(FrostSigningEvents.DECLINE).map { it.content })
    }

    @Test
    fun `a 10050 of public relays is put to the member, named for what it is`(): Unit = runBlocking {
        val sent = proposeRelayList(listOf("wss://fine.example.com"))
        harness.deliver(sent, harness.members[1])
        val session = assertNotNull(harness.db.frostSigningSessionDao().getSessionById(sent.session.id))
        assertNull(session.signDeclinedAt)
        val items = harness.db.frostSigningSessionDao().getItems(session.id)
        assertTrue(FrostSigningManager.isAwaitingApproval(session, items))
        val summary = ProposedEvent.summarize(Event.fromJson(items.single().unsignedEventJson))
        assertEquals("Where the group receives messages", summary.label)
        assertEquals("wss://fine.example.com/", summary.detail)
    }
}
