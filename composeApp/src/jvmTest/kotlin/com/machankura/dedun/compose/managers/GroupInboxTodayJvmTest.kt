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
 * - a peer's proposal to sign a kind 10044 (an encryption key) or a kind 13 (a NIP-17 seal)
 *   reaches the member as a bare number, and nothing declines it. Phase 5 and Phase 8 put a
 *   refusal in front of each;
 * - the only gift wraps the app asks relays for are its own identity's. Phase 6 adds the
 *   group's.
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
    fun `a peer's inbox key reaches the member as a number, and can be signed`() {
        val sent = proposeAndDeliver(10044, arrayOf(arrayOf("n", "e".repeat(64))), "")
        assertPutToTheMemberByNumber(sent, 10044)
    }

    @Test
    fun `a peer's sealed ciphertext reaches the member as a number, and can be signed`() {
        // The member is asked to sign bytes it cannot read: the rubber stamp
        // docs/dedun-wallet-removal.md says a Dedun device must not be. Phase 8 declines it.
        val sent = proposeAndDeliver(13, arrayOf(arrayOf("n", "e".repeat(64))), "AnOpaqueCiphertext")
        assertPutToTheMemberByNumber(sent, 13)
    }

    @Test
    fun `the only gift wraps asked for are the identity's own`() {
        val filter = LiveSubscriptionManager.giftWrapFilter(alice)
        assertEquals(listOf(1059), filter.kinds)
        assertEquals(mapOf("p" to listOf(alice)), filter.tags)
    }
}
