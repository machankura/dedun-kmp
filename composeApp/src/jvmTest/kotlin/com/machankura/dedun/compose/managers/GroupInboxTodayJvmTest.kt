package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.text.ProposedEvent

/**
 * What Dedun does today with the events a group's inbox is made of (docs/group-inbox.md,
 * Phase 1). Written to pass now and flipped on purpose by the phase that changes each:
 *
 * - a peer's proposal to sign a kind 13 (a NIP-17 seal) reaches the member as a bare number,
 *   and nothing declines it. Phase 8 puts a refusal in front of it, as Phase 5 did for a kind
 *   10044 (an encryption key), which this pins declined now;
 * - the identity's own gift wraps are asked for on their own subscription, beside the
 *   group's that Phase 6 added.
 */
class GroupInboxTodayJvmTest {
    private val harness = SigningProposalHarness()
    private val alice = harness.members[0]
    private val bob = harness.members[1]

    @AfterTest
    fun close() = harness.close()

    private fun proposeAndDeliver(kind: Int, tags: Array<Array<String>>, content: String) = runBlocking {
        val sent = harness.propose(alice) { db, room ->
            FrostSigningManager.proposeSigningBatch(
                db, room, alice,
                events = listOf(EventTemplate<Event>(1_700_000_000L, kind, tags, content)),
            )
        }
        harness.deliver(sent, bob)
        sent
    }

    private fun assertPutToTheMemberByNumber(sent: SigningProposalHarness.Sent, kind: Int) = runBlocking {
        val session = assertNotNull(harness.db.frostSigningSessionDao().getSessionById(sent.session.id))
        assertNull(session.signDeclinedAt, "nothing declines it today")
        assertTrue(FrostSigningManager.isAwaitingApproval(session, harness.db.frostSigningSessionDao().getItems(session.id)))
        assertEquals(emptyList(), harness.queued(FrostSigningEvents.DECLINE))

        val item = harness.db.frostSigningSessionDao().getItems(session.id).single()
        assertEquals("Event of kind $kind", ProposedEvent.summarize(Event.fromJson(item.unsignedEventJson)).label)
    }

    @Test
    fun `a peer's inbox key nobody here made is declined out loud`() = runBlocking {
        // Flipped by Phase 5: the signer refuses an announcement of a key the group's
        // admins did not make together (docs/group-inbox.md, D5).
        val sent = proposeAndDeliver(10044, arrayOf(arrayOf("n", "e".repeat(64))), "")
        val session = assertNotNull(harness.db.frostSigningSessionDao().getSessionById(sent.session.id))
        assertNotNull(session.signDeclinedAt)
        assertEquals(listOf(InboxRefusal.KEY_NOT_MADE), harness.queued(FrostSigningEvents.DECLINE).map { it.content })
    }

    @Test
    fun `a peer's sealed ciphertext reaches the member as a number, and can be signed`() {
        // The member is asked to sign bytes it cannot read: the rubber stamp
        // docs/dedun-wallet-removal.md says a Dedun device must not be. Phase 8 declines it.
        val sent = proposeAndDeliver(13, arrayOf(arrayOf("n", "e".repeat(64))), "AnOpaqueCiphertext")
        assertPutToTheMemberByNumber(sent, 13)
    }

    @Test
    fun `the identity's own gift wraps are asked for apart from its groups'`() {
        // Flipped by Phase 6: a group's mail has its own subscription
        // (`LiveSubscriptionManager.groupInboxFilters`), and the identity's stays its own.
        val filter = LiveSubscriptionManager.giftWrapFilter(alice)
        assertEquals(listOf(1059), filter.kinds)
        assertEquals(mapOf("p" to listOf(alice)), filter.tags)
        assertEquals(mapOf("p" to listOf(harness.roomId)), LiveSubscriptionManager.groupInboxFilters(listOf(harness.roomId)).single().tags)
    }
}
