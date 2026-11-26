package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip59Giftwrap.rumors.RumorAssembler
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealEvent
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import fr.acinq.bitcoin.Crypto
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.InboxKeyState
import com.machankura.dedun.compose.nostr.inbox.InboxConversation
import com.machankura.dedun.compose.nostr.inbox.InboxSeal
import com.machankura.dedun.compose.nostr.inbox.InboxWire
import com.machankura.dedun.compose.nostr.inbox.InboxWrap

/**
 * Reading the group's inbox (docs/group-inbox.md, Phase 6): mail a stranger writes to the group
 * opens on every admin's device and on no other, whatever the stranger's app does with the
 * announced key, and what nobody can open is counted rather than lost.
 */
class GroupInboxReadJvmTest {
    private val harness = GroupInboxHarness(watchers = listOf("dineo"))
    private val zola = KeyPair(Crypto.sha256("zola, who writes to the group".encodeToByteArray()))
    private val zolaKey = zola.pubKey.toHexKey()

    @AfterTest
    fun close() = harness.close()

    private suspend fun group() = listOf("alice", "bob", "carol", "dineo").map { harness.device(it) }

    private suspend fun keyed(): List<GroupInboxHarness.Device> {
        val devices = group()
        val alice = devices[0]
        InboxKeyRitualManager.propose(alice.db, alice.room(), alice.publicKey, alice.secret)
        harness.pump()
        return devices
    }

    private fun rumor(text: String, createdAt: Long = 1_700_000_000L): Event = RumorAssembler.assembleRumor(
        pubKey = zolaKey,
        ev = EventTemplate<Event>(createdAt, 14, arrayOf(arrayOf("p", harness.roomId)), text),
    )

    /** A stock NIP-17 seal from Zola's identity, to [toKey]. */
    private fun seal(message: Event, toKey: HexKey): Event = NostrSignerSync(zola).sign<SealEvent>(
        1_700_000_000L, 13, emptyArray(),
        InboxWire.encrypt(InboxSeal.rumorJson(message), InboxWire.conversationKey(zola.privKey!!, toKey)),
    )

    private suspend fun GroupInboxHarness.Device.inboxKey(): HexKey = keys().first().publicKey

    private suspend fun GroupInboxHarness.Device.receive(wrap: Event) = GroupInboxManager.receive(db, wrap, publicKey)

    @Test
    fun `mail written to the group opens on every admin's device and on nobody else's`() = runBlocking<Unit> {
        val (alice, bob, carol, dineo) = keyed()
        val e = alice.inboxKey()
        val message = rumor("Can I join the reading group?")
        val wrap = InboxWrap.wrap(seal(message, e), harness.roomId, e)

        listOf(alice, bob, carol).forEach { admin ->
            val filed = assertNotNull(admin.receive(wrap), "${admin.name} opens it")
            assertEquals(message.id, filed.rumorId)
            assertEquals(zolaKey, filed.senderPublicKey)
            assertFalse(filed.outgoing)
            assertEquals(listOf(zolaKey), filed.participantList())
            assertEquals(InboxConversation.idOf(listOf(zolaKey)), filed.conversationId)
            assertEquals("Can I join the reading group?", Event.fromJson(filed.rumorJson).content)
        }
        assertNull(dineo.receive(wrap), "a member who is not an admin holds nothing to open it with")
        assertEquals(emptyList(), dineo.db.groupInboxDao().getMessages(harness.roomId))
    }

    @Test
    fun `a wrap tagged with the inbox key rather than the group still arrives`() = runBlocking<Unit> {
        val (alice, _, _, _) = keyed()
        val e = alice.inboxKey()
        // quartz's own builder tags the key it encrypts to: what an app that knows only the
        // announcement, and not #2361, sends.
        val wrap = GiftWrapEvent.create(event = seal(rumor("Tagged to the key"), e), recipientPubKey = e)
        assertEquals("Tagged to the key", Event.fromJson(assertNotNull(alice.receive(wrap)).rumorJson).content)
    }

    @Test
    fun `mail to the group's npub, which nobody can open, is counted and kept`() = runBlocking<Unit> {
        val (alice, _, _, _) = keyed()
        val legacy = InboxWrap.wrap(seal(rumor("From an app with no NIP-4E"), harness.roomId), harness.roomId, harness.roomId)

        assertNull(alice.receive(legacy))
        assertEquals(1, alice.db.groupInboxDao().observeUnopenedCount(harness.roomId).first())
        assertNotNull(alice.db.groupInboxDao().getWrap(legacy.id), "kept in the ledger")
        assertEquals(emptyList(), alice.db.groupInboxDao().getMessages(harness.roomId))
    }

    @Test
    fun `one rumor in two wraps is one message, and a repeat changes nothing`() = runBlocking<Unit> {
        val (alice, _, _, _) = keyed()
        val e = alice.inboxKey()
        val message = rumor("Sent twice")
        val first = InboxWrap.wrap(seal(message, e), harness.roomId, e)
        val second = InboxWrap.wrap(seal(message, e), harness.roomId, e)

        assertNotNull(alice.receive(first))
        assertNull(alice.receive(first), "the same wrap again is not news")
        alice.receive(second)
        assertEquals(listOf(message.id), alice.db.groupInboxDao().getMessages(harness.roomId).map { it.rumorId })
        assertEquals(1, alice.db.groupInboxDao().observeUnreadCount(harness.roomId).first())
    }

    @Test
    fun `mail to a retired key still opens`() = runBlocking<Unit> {
        val (alice, bob, _, _) = keyed()
        val old = alice.inboxKey()
        InboxKeyRitualManager.propose(bob.db, bob.room(), bob.publicKey, bob.secret)
        harness.pump()
        assertEquals(2, alice.keys().size)

        val late = InboxWrap.wrap(seal(rumor("Written from an old 10044"), old), harness.roomId, old)
        assertEquals(0, assertNotNull(alice.receive(late)).generation)
    }

    @Test
    fun `mail filed while this device lacked the key opens once it has it`() = runBlocking<Unit> {
        val (alice, _, _, _) = keyed()
        val held = alice.keys().single()
        val wrap = InboxWrap.wrap(seal(rumor("Written before the key reached this device"), held.publicKey), harness.roomId, held.publicKey)

        // As though the key were still on its way here, with an older one held: the newer
        // generation's secret is missing, so the wrap goes into the ledger unopened.
        alice.db.inboxKeyDao().upsertKey(held.copy(generation = 7, publicKey = "f".repeat(64), secretKey = "11".repeat(32)))
        alice.db.inboxKeyDao().upsertKey(held.copy(secretKey = null))
        assertNull(alice.receive(wrap))
        assertEquals(1, alice.db.groupInboxDao().observeUnopenedCount(harness.roomId).first())

        // The key arrives -- a ceremony finishing runs exactly this.
        alice.db.inboxKeyDao().upsertKey(held)
        assertEquals(1, GroupInboxManager.reopen(alice.db, harness.roomId, alice.publicKey))
        assertEquals(0, alice.db.groupInboxDao().observeUnopenedCount(harness.roomId).first())
        assertEquals(listOf("Written before the key reached this device"), alice.db.groupInboxDao().getMessages(harness.roomId).map { Event.fromJson(it.rumorJson).content })
    }

    @Test
    fun `an admin no longer forgets the inbox, and says so`() = runBlocking<Unit> {
        val (alice, _, _, _) = keyed()
        val e = alice.inboxKey()
        alice.receive(InboxWrap.wrap(seal(rumor("Before"), e), harness.roomId, e))

        GroupInboxManager.forgetIfNotAdmin(alice.db, harness.roomId, alice.publicKey, stillAdmin = true)
        assertEquals(1, alice.db.groupInboxDao().getMessages(harness.roomId).size, "an admin keeps it")

        GroupInboxManager.forgetIfNotAdmin(alice.db, harness.roomId, alice.publicKey, stillAdmin = false)
        assertEquals(emptyList(), alice.db.groupInboxDao().getMessages(harness.roomId))
        assertTrue(alice.keys().all { it.secretKey == null && it.state == InboxKeyState.WIPED })
        assertEquals(1, alice.lines(setOf(ChatMessage.TYPE_INBOX_FORGOTTEN)).size)
        assertNull(alice.receive(InboxWrap.wrap(seal(rumor("After"), e), harness.roomId, e)), "and opens nothing after")

        GroupInboxManager.forgetIfNotAdmin(alice.db, harness.roomId, alice.publicKey, stillAdmin = false)
        assertEquals(1, alice.lines(setOf(ChatMessage.TYPE_INBOX_FORGOTTEN)).size, "said once")
    }

    @Test
    fun `the subscription asks for mail to the group and to each key it holds`() {
        val filters = LiveSubscriptionManager.groupInboxFilters(listOf(harness.roomId, "e".repeat(64), harness.roomId))
        assertEquals(listOf(1059), filters.single().kinds)
        assertEquals(mapOf("p" to listOf(harness.roomId, "e".repeat(64)).sorted()), filters.single().tags)
    }
}
