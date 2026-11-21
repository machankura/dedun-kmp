package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.ExperimentalFeatures
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.InboxKeyCeremonyStage
import com.machankura.dedun.compose.database.model.InboxKeyState
import com.machankura.dedun.compose.nostr.inbox.InboxKeyRitualEvents
import com.machankura.dedun.compose.nostr.inbox.InboxWire

/**
 * The group's inbox key ceremony across devices (docs/group-inbox.md, Phase 4): three admins
 * and a member who is not one, each on their own database, with what each sends handed to the
 * others.
 */
class InboxKeyRitualJvmTest {
    private val harness = GroupInboxHarness(watchers = listOf("dineo"))

    @AfterTest
    fun close() = harness.close()

    private suspend fun group() = listOf("alice", "bob", "carol", "dineo").map { harness.device(it) }

    @Test
    fun `every admin ends with the same secret, and it is the key the ceremony made`() = runBlocking<Unit> {
        val (alice, bob, carol, dineo) = group()

        val ceremony = InboxKeyRitualManager.propose(alice.db, alice.room(), alice.publicKey, alice.secret)
        assertEquals(0, ceremony.generation)
        harness.pump()

        val keys = listOf(alice, bob, carol).map { assertNotNull(it.keys().singleOrNull(), "${it.name} holds the key") }
        assertEquals(1, keys.map { it.secretKey }.distinct().size, "every admin holds one secret")
        keys.forEach { key ->
            assertEquals(InboxKeyState.HELD, key.state, "made, not yet announced")
            assertEquals(key.publicKey, InboxWire.publicKeyOf(assertNotNull(key.secretKey).hexToByteArray()))
            assertNotNull(key.recoveryData)
        }
        listOf(alice, bob, carol).forEach {
            assertEquals(InboxKeyCeremonyStage.COMPLETE, it.ceremony(ceremony.id)?.stage)
            assertEquals(keys.first().publicKey, it.ceremony(ceremony.id)?.inboxPublicKey)
        }

        // The member who is not an admin watched it happen and holds nothing.
        assertEquals(emptyList(), dineo.keys())
        assertEquals(InboxKeyCeremonyStage.COMPLETE, dineo.ceremony(ceremony.id)?.stage)
        assertEquals(1, dineo.lines(setOf(ChatMessage.TYPE_INBOX_KEY_STARTED)).size)
        assertEquals(1, dineo.lines(setOf(ChatMessage.TYPE_INBOX_KEY_COMPLETE)).size)
    }

    @Test
    fun `nobody is asked, and each device says it once`() = runBlocking<Unit> {
        val (alice, bob, carol, _) = group()
        InboxKeyRitualManager.propose(alice.db, alice.room(), alice.publicKey, alice.secret)
        harness.pump()
        harness.pump() // redelivery changes nothing

        listOf(alice, bob, carol).forEach { device ->
            assertEquals(1, device.lines(setOf(ChatMessage.TYPE_INBOX_KEY_STARTED)).size)
            assertEquals(1, device.lines(setOf(ChatMessage.TYPE_INBOX_KEY_COMPLETE)).size)
            assertEquals(0, device.lines(setOf(ChatMessage.TYPE_INBOX_KEY_FAILED)).size)
        }
    }

    @Test
    fun `a proposal that leaves out an admin fails out loud on every device`() = runBlocking<Unit> {
        val (alice, bob, carol, dineo) = group()
        // Alice's device does not yet see Carol as an admin; everyone else does.
        harness.admins = setOf(alice.publicKey, bob.publicKey)
        val ceremony = InboxKeyRitualManager.propose(alice.db, alice.room(), alice.publicKey, alice.secret)
        harness.admins = setOf(alice.publicKey, bob.publicKey, carol.publicKey)
        harness.pump()

        listOf(alice, bob, carol, dineo).forEach { device ->
            val seen = assertNotNull(device.ceremony(ceremony.id))
            assertEquals(InboxKeyCeremonyStage.FAILED, seen.stage, "${device.name} sees it failed")
            assertTrue(seen.failureReason.orEmpty().contains("leaves out 1 admin"), seen.failureReason)
            assertEquals(emptyList(), device.keys())
        }
    }

    @Test
    fun `a proposal that names someone who is not an admin fails out loud`() = runBlocking<Unit> {
        val (alice, bob, carol, dineo) = group()
        harness.admins = setOf(alice.publicKey, bob.publicKey, carol.publicKey, dineo.publicKey)
        val ceremony = InboxKeyRitualManager.propose(alice.db, alice.room(), alice.publicKey, alice.secret)
        harness.admins = setOf(alice.publicKey, bob.publicKey, carol.publicKey)
        harness.pump()

        listOf(bob, carol).forEach { device ->
            val seen = assertNotNull(device.ceremony(ceremony.id))
            assertEquals(InboxKeyCeremonyStage.FAILED, seen.stage)
            assertTrue(seen.failureReason.orEmpty().contains("is not an admin"), seen.failureReason)
        }
        assertEquals(emptyList(), dineo.keys())
    }

    @Test
    fun `a ceremony interrupted mid-round finishes when its admins come back`() = runBlocking<Unit> {
        val (alice, bob, carol, dineo) = group()
        val ceremony = InboxKeyRitualManager.propose(alice.db, alice.room(), alice.publicKey, alice.secret)
        // Carol is offline for the first rounds: the rest can go no further than her host key.
        harness.pump(except = setOf(carol))
        assertEquals(emptyList(), alice.keys())
        assertTrue(assertNotNull(alice.ceremony(ceremony.id)).isRunning)

        harness.pump()
        assertEquals(1, alice.keys().size)
        assertEquals(alice.keys().single().publicKey, carol.keys().single().publicKey)
        assertEquals(alice.keys().single().publicKey, bob.keys().single().publicKey)
        assertEquals(emptyList(), dineo.keys())
    }

    @Test
    fun `a message delivered before its proposal is replayed, not lost`() = runBlocking<Unit> {
        val (alice, bob, _, _) = group()
        InboxKeyRitualManager.propose(alice.db, alice.room(), alice.publicKey, alice.secret)

        // Bob gets Alice's host key ahead of her proposal.
        val rows = alice.db.marmotInnerEventDao().getByChatRoomAndKinds(harness.roomId, InboxKeyRitualEvents.ALL.toList())
        val hostKey = rows.single { it.kind == InboxKeyRitualEvents.HOST_KEY }
        val proposal = rows.single { it.kind == InboxKeyRitualEvents.PROPOSAL }
        listOf(hostKey, proposal).forEach { row ->
            bob.db.marmotInnerEventDao().upsert(row)
            bob.received += row.id
            InboxKeyRitualManager.process(
                bob.db, bob.room(),
                com.vitorpamplona.quartz.nip01Core.core.Event(row.id, row.publicKey, row.createdAt.epochSeconds, row.kind, row.tags, row.content, ""),
                bob.publicKey, bob.secret,
            )
        }
        val ceremonyOnBob = assertNotNull(bob.db.inboxKeyDao().getLatestCeremony(harness.roomId, bob.publicKey))
        assertNotNull(bob.db.inboxKeyDao().getMessage(ceremonyOnBob.id, alice.publicKey, InboxKeyRitualEvents.HOST_KEY))

        harness.pump()
        assertEquals(alice.keys().single().publicKey, bob.keys().single().publicKey)
    }

    @Test
    fun `an admin can abandon a ceremony, and nobody else can`() = runBlocking<Unit> {
        val (alice, bob, carol, dineo) = group()
        val ceremony = InboxKeyRitualManager.propose(alice.db, alice.room(), alice.publicKey, alice.secret)
        harness.pump(except = setOf(carol))

        assertTrue(!InboxKeyRitualManager.abandon(dineo.db, dineo.room(), ceremony.id), "a member who is not in it cannot end it")
        assertTrue(InboxKeyRitualManager.abandon(bob.db, bob.room(), ceremony.id))
        harness.pump()

        listOf(alice, bob, carol, dineo).forEach {
            assertEquals(InboxKeyCeremonyStage.FAILED, it.ceremony(ceremony.id)?.stage, "${it.name} sees it ended")
            assertEquals(emptyList(), it.keys())
        }
    }

    @Test
    fun `only an admin can start one, one at a time, and not in a release build`() = runBlocking<Unit> {
        val (alice, _, _, dineo) = group()

        assertNotNull(InboxKeyRitualManager.refusal(dineo.db, dineo.room(), dineo.publicKey))
        assertFailsWith<IllegalStateException> { InboxKeyRitualManager.propose(dineo.db, dineo.room(), dineo.publicKey, dineo.secret) }

        assertNull(InboxKeyRitualManager.refusal(alice.db, alice.room(), alice.publicKey))
        val first = InboxKeyRitualManager.propose(alice.db, alice.room(), alice.publicKey, alice.secret)
        val again = InboxKeyRitualManager.propose(alice.db, alice.room(), alice.publicKey, alice.secret)
        assertEquals(first.id, again.id, "a running ceremony is handed back, not doubled")

        ExperimentalFeatures.groupInboxOverride = false
        assertNotNull(InboxKeyRitualManager.refusal(alice.db, alice.room(), alice.publicKey))
    }

    @Test
    fun `a second ceremony makes the next generation, and a different key`() = runBlocking<Unit> {
        val (alice, bob, _, _) = group()
        InboxKeyRitualManager.propose(alice.db, alice.room(), alice.publicKey, alice.secret)
        harness.pump()
        val second = InboxKeyRitualManager.propose(bob.db, bob.room(), bob.publicKey, bob.secret)
        assertEquals(1, second.generation)
        harness.pump()

        val keys = alice.keys()
        assertEquals(listOf(1, 0), keys.map { it.generation })
        assertTrue(keys[0].publicKey != keys[1].publicKey)
        assertEquals(bob.keys().map { it.publicKey }, keys.map { it.publicKey })
    }

    @Test
    fun `host keys are bound to the room and come from the nsec alone`() {
        val secret = harness.secrets.getValue("alice")
        val here = InboxKeyRitualManager.deriveHostPublicKey(secret, harness.roomId)
        assertEquals(here, InboxKeyRitualManager.deriveHostPublicKey(secret, harness.roomId))
        assertTrue(here != InboxKeyRitualManager.deriveHostPublicKey(secret, "1".repeat(64)))
        assertTrue(here != ChillDkgRitualManager.deriveHostPublicKey(secret), "not the signing ceremony's host key")
    }
}
