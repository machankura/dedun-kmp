package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
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
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.database.model.InboxKeyState
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.repository.DatabaseGroupInboxRepository
import com.machankura.dedun.compose.network.dto.RelayDTO
import com.machankura.dedun.compose.network.relays.EventPublishTransport
import com.machankura.dedun.compose.network.relays.NostrPublishResult
import com.machankura.dedun.compose.network.sockets.NostrIncomingMessage
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.nostr.inbox.InboxAnnouncement
import com.machankura.dedun.compose.text.ProposedEvent

/**
 * The group announcing its inbox (docs/group-inbox.md, Phase 5): the first announcement is asked,
 * a rotation's is vouched for, a wrong one is declined out loud, and the device that asked owes
 * what was signed to relays.
 */
class InboxAnnouncementJvmTest {
    private val harness = GroupInboxHarness()

    @AfterTest
    fun close() = harness.close()

    private suspend fun group() = listOf("alice", "bob", "carol").map { harness.device(it) }

    private suspend fun GroupInboxHarness.Device.sessions(): List<FrostSigningSession> =
        db.frostSigningSessionDao().getSessionsForChatRoom(harness.roomId)

    private suspend fun GroupInboxHarness.Device.events(session: FrostSigningSession): List<Event> =
        db.frostSigningSessionDao().getItems(session.id).map { Event.fromJson(it.unsignedEventJson) }

    private suspend fun GroupInboxHarness.Device.awaiting(session: FrostSigningSession): Boolean {
        val current = assertNotNull(db.frostSigningSessionDao().getSessionById(session.id))
        return FrostSigningManager.isAwaitingApproval(current, db.frostSigningSessionDao().getItems(session.id))
    }

    /** Makes the first key and has Bob approve its announcement. */
    private suspend fun announced(alice: GroupInboxHarness.Device, bob: GroupInboxHarness.Device): FrostSigningSession {
        InboxKeyRitualManager.propose(alice.db, alice.room(), alice.publicKey, alice.secret)
        harness.pump()
        val session = alice.sessions().single()
        FrostSigningManager.approve(bob.db, bob.room(), session.id)
        harness.pump()
        return session
    }

    @Test
    fun `the first key's announcement is the group deciding to have an inbox, and is asked`() = runBlocking<Unit> {
        val (alice, bob, carol) = group()
        InboxKeyRitualManager.propose(alice.db, alice.room(), alice.publicKey, alice.secret)
        harness.pump()

        val session = alice.sessions().single()
        val kinds = alice.events(session).map { it.kind }
        assertEquals(listOf(10044, 10050), kinds, "the key and where mail goes, in one batch")
        assertEquals(alice.keys().single().publicKey, InboxAnnouncement.groupKeyOf(alice.events(session)[0]))

        listOf(bob, carol).forEach { assertTrue(it.awaiting(session), "${it.name} is asked") }
        assertEquals("The group's inbox key", ProposedEvent.summarize(bob.events(session)[0]).label)
        assertEquals("Where the group receives private messages", ProposedEvent.summarize(bob.events(session)[1]).label)

        FrostSigningManager.approve(bob.db, bob.room(), session.id)
        harness.pump()

        listOf(alice, bob).forEach { device ->
            assertEquals(FrostSigningStage.COMPLETE, device.db.frostSigningSessionDao().getSessionById(session.id)?.stage)
            assertEquals(InboxKeyState.ANNOUNCED, device.keys().single().state)
            assertEquals(1, device.lines(setOf(ChatMessage.TYPE_INBOX_ANNOUNCED)).size)
            assertEquals(1, device.lines(setOf(ChatMessage.TYPE_INBOX_RELAYS_ANNOUNCED)).size)
        }
    }

    @Test
    fun `a rotation's announcement is signed by every admin's device unasked`() = runBlocking<Unit> {
        val (alice, bob, carol) = group()
        announced(alice, bob)

        InboxKeyRitualManager.propose(bob.db, bob.room(), bob.publicKey, bob.secret)
        harness.pump()

        val rotation = bob.sessions().first { s -> bob.events(s).singleOrNull()?.kind == 10044 }
        listOf(alice, bob, carol).forEach { device ->
            assertEquals(FrostSigningStage.COMPLETE, device.db.frostSigningSessionDao().getSessionById(rotation.id)?.stage, "${device.name} signed it unasked")
            val keys = device.keys()
            assertEquals(listOf(1, 0), keys.map { it.generation })
            assertEquals(InboxKeyState.ANNOUNCED, keys[0].state)
            assertEquals(InboxKeyState.RETIRED, keys[1].state)
            assertNotNull(keys[1].secretKey, "a retired key is kept, to open with")
        }
        assertTrue(alice.lines(setOf(ChatMessage.TYPE_INBOX_ANNOUNCED)).any { it.content.startsWith("The group changed its inbox key") })
    }

    private suspend fun proposeByHand(from: GroupInboxHarness.Device, vararg templates: EventTemplate<Event>): FrostSigningSession =
        FrostSigningManager.proposeSigningBatch(from.db, from.room(), from.publicKey, events = templates.toList())

    private suspend fun declineReason(on: GroupInboxHarness.Device, session: FrostSigningSession): String? {
        val current = assertNotNull(on.db.frostSigningSessionDao().getSessionById(session.id))
        assertNotNull(current.signDeclinedAt, "${on.name} declined")
        return on.db.marmotInnerEventDao().getByChatRoomAndKinds(harness.roomId, listOf(FrostSigningEvents.DECLINE))
            .firstOrNull { it.publicKey == on.publicKey }?.content
    }

    @Test
    fun `an inbox key the admins never made is declined out loud`() = runBlocking<Unit> {
        val (alice, bob, _) = group()
        val session = proposeByHand(alice, InboxAnnouncement.keyTemplate("e".repeat(64), 1_700_000_000L))
        harness.pump()
        assertEquals(InboxRefusal.KEY_NOT_MADE, declineReason(bob, session))
    }

    @Test
    fun `a key the group has since replaced is declined`() = runBlocking<Unit> {
        val (alice, bob, _) = group()
        announced(alice, bob)
        val old = alice.keys().single().publicKey
        InboxKeyRitualManager.propose(bob.db, bob.room(), bob.publicKey, bob.secret)
        harness.pump()

        val revert = proposeByHand(alice, InboxAnnouncement.keyTemplate(old, 1_700_000_000L))
        harness.pump()
        assertEquals(InboxRefusal.KEY_NOT_NEWEST, declineReason(bob, revert))
    }

    @Test
    fun `a key made by admins the room no longer has is declined`() = runBlocking<Unit> {
        val (alice, bob, carol) = group()
        InboxKeyRitualManager.propose(alice.db, alice.room(), alice.publicKey, alice.secret)
        harness.pump()
        val session = alice.sessions().single()
        // Carol stops being an admin before Bob decides.
        harness.admins = setOf(alice.publicKey, bob.publicKey)
        FrostSigningManager.approve(bob.db, bob.room(), session.id)
        assertEquals(InboxRefusal.KEY_NOT_ADMINS, declineReason(bob, session))
    }

    @Test
    fun `relays a stranger could not reach are declined`() = runBlocking<Unit> {
        val (alice, bob, _) = group()
        val session = proposeByHand(alice, EventTemplate(1_700_000_000L, 10050, arrayOf(arrayOf("relay", "ws://localhost:7777")), ""))
        harness.pump()
        assertEquals(InboxRefusal.RELAYS_SHAPE, declineReason(bob, session))
    }

    /** Answers every relay with an OK, and keeps what it was handed. */
    private class Transport : EventPublishTransport {
        val sent = mutableListOf<NostrEvent>()
        override suspend fun publishEvent(nostrEvent: NostrEvent, relays: Set<RelayDTO>): Flow<NostrPublishResult> {
            sent += nostrEvent
            return flowOf(*relays.map { NostrPublishResult(result = NostrIncomingMessage.OkMessage(eventId = nostrEvent.id, success = true), relayUrl = it.url) }.toTypedArray())
        }
    }

    @Test
    fun `the device that asked owes what was signed, and sends it as signed without a feed row`() = runBlocking<Unit> {
        val (alice, bob, _) = group()
        val session = announced(alice, bob)

        assertEquals(emptyList(), bob.db.groupInboxDao().getPublicationsFor(session.id), "only the proposer sends")
        val owed = alice.db.groupInboxDao().getPublicationsFor(session.id)
        assertEquals(setOf(10044, 10050), owed.map { it.kind }.toSet())

        val transport = Transport()
        val publisher = GroupInboxPublisher(DatabaseGroupInboxRepository(alice.db, this), transport)
        owed.forEach { assertTrue(publisher.publish(it)) }

        val signed = alice.db.groupSignedEventDao().getByChatRoomIdAndKind(harness.roomId, 10044).single()
        val wire = transport.sent.single { it.kind == 10044 }
        assertEquals(signed.id, wire.id)
        assertEquals(signed.signature, wire.sig)
        assertNull(alice.db.nostrEventDao().getNostrEventById(signed.id), "no feed row for the group's event")
        assertTrue(alice.db.groupInboxDao().getPublicationsFor(session.id).all { it.publishedAt != null })
    }
}
