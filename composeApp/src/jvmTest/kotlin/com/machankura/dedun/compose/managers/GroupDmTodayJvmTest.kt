package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.text.ProposedEvent

/**
 * What Dedun does today with a group's direct messages, pinned before docs/group-threshold-ecdh.md
 * changes it (Phase 1). Each test here is meant to be flipped, on purpose, by the phase named
 * beside it.
 */
class GroupDmTodayJvmTest {
    private val harness = SigningProposalHarness()
    private val alice = harness.members[0]
    private val bob = harness.members[1]

    @BeforeTest
    fun seeAdmins() {
        RoomAdmins.override = { id -> if (id == harness.roomId) RoomAdmins(harness.members.toSet(), governed = false) else null }
    }

    @AfterTest
    fun close() {
        RoomAdmins.override = null
        harness.close()
    }

    private val relay = "wss://relay.example"

    /** A person on any NIP-17 app, writing to the group's npub. */
    private fun wrapToGroup(): NostrEvent {
        val sender = NostrSignerSync(KeyPair())
        val seal = sender.signNormal<SealEvent>(
            createdAt = 1_700_000_000,
            kind = SealEvent.KIND,
            tags = emptyArray(),
            content = sender.nip44Encrypt("""{"kind":14,"content":"dumela"}""", harness.roomId),
        )
        val wrap = GiftWrapEvent.create(event = seal, recipientPubKey = harness.roomId, createdAt = 1_700_000_100)
        return NostrEvent(
            id = wrap.id, pubKey = wrap.pubKey, kind = wrap.kind, tags = wrap.tags,
            content = wrap.content, sig = wrap.sig, createdAt = Instant.fromEpochSeconds(wrap.createdAt),
        )
    }

    /** Phase 7 flips this: the group's subscription hands such a wrap to its own pipeline. */
    @Test
    fun `a wrap to the group's npub is stored and never opened`() = runBlocking {
        val db = harness.openDevice(alice)
        val wrap = wrapToGroup()
        db.nostrDao().storeNostrEvent(wrap, relay, listOf(relay), 0, KeyPair())

        assertNotNull(db.nostrEventDao().getNostrEventById(wrap.id))
        assertEquals(listOf(wrap.id), db.giftWrapMessageDao().getAllGiftWrapMessages().map { it.id })
        assertEquals(emptyList(), db.giftWrapSealDao().getAllGiftWrapSeals(), "nothing on the device can open it")
    }

    private fun proposeKind(kind: Int, content: String, tags: Array<Array<String>> = emptyArray()) = runBlocking {
        harness.propose(alice) { db, room ->
            FrostSigningManager.proposeSigning(db, room, alice, kind = kind, tags = tags, content = content)
        }
    }

    private fun assertAskedBlind(sent: SigningProposalHarness.Sent, kind: Int) = runBlocking {
        harness.deliver(sent, bob)
        val session = assertNotNull(harness.db.frostSigningSessionDao().getSessionById(sent.session.id))
        assertNull(session.signDeclinedAt, "not declined")
        val items = harness.db.frostSigningSessionDao().getItems(session.id)
        assertTrue(FrostSigningManager.isAwaitingApproval(session, items), "put to the member")
        assertEquals(emptyList(), harness.queued(FrostSigningEvents.DECLINE))
        val event = Event.fromJson(items.single().unsignedEventJson)
        assertEquals("Event of kind $kind", ProposedEvent.summarize(event).label)
    }

    /** Phase 9 flips this: a seal no signer can read is declined unless D13's checks pass. */
    @Test
    fun `a peer's kind 13 is asked as a raw kind, and can be signed blind`() {
        assertAskedBlind(proposeKind(SealEvent.KIND, "AqH1ciphertext"), SealEvent.KIND)
    }

    /** Flipped by Phase 6: a 10050 is named for what it is, and declined unless its relays are public (GroupDmRelaysJvmTest). */
    @Test
    fun `a peer's kind 10050 is no longer asked as a raw kind`() = runBlocking {
        val sent = proposeKind(10050, "", arrayOf(arrayOf("relay", "wss://relay.example")))
        harness.deliver(sent, bob)
        val items = harness.db.frostSigningSessionDao().getItems(sent.session.id)
        assertEquals("Where the group receives messages", ProposedEvent.summarize(Event.fromJson(items.single().unsignedEventJson)).label)
    }

    /** Flipped by Phase 7: the group's mail has its own subscription. */
    @Test
    fun `there is a subscription for a group's mail`() {
        assertEquals("live-group-dm", LiveSubscriptionManager.GROUP_DM_SUB_ID)
    }
}
