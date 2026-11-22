package com.machankura.dedun.compose.managers

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import com.vitorpamplona.quartz.nip59Giftwrap.rumors.RumorAssembler
import com.vitorpamplona.quartz.nip59Giftwrap.seals.SealEvent
import fr.acinq.bitcoin.Crypto
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.model.InboxKeyCeremonyStage
import com.machankura.dedun.compose.database.model.InboxKeyState
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.nostr.inbox.InboxSeal
import com.machankura.dedun.compose.nostr.inbox.InboxWire
import com.machankura.dedun.compose.nostr.inbox.InboxWrap

/**
 * The inbox key changing with the group's admins (docs/group-inbox.md, Phase 9): nobody is asked,
 * one admin's device starts it, a removed admin cannot open what is sent to the new key, a promoted
 * one holds only the new key, and a reply proposed before the change still goes out after it.
 */
class InboxRotationJvmTest {
    private val harness = GroupInboxHarness()
    private val zola = KeyPair(Crypto.sha256("zola, who writes to the group".encodeToByteArray()))
    private val now get() = kotlin.time.Clock.System.now().epochSeconds

    @AfterTest
    fun close() = harness.close()

    private lateinit var devices: Map<String, GroupInboxHarness.Device>
    private fun device(name: String) = devices.getValue(name)

    private suspend fun open(names: List<String> = listOf("alice", "bob", "carol")) {
        devices = names.associateWith { harness.device(it) }
    }

    /** Makes and announces the first key among whoever the harness has as admins. */
    private suspend fun announced(by: String = "alice", approver: String = "bob") {
        val proposer = device(by)
        InboxKeyRitualManager.propose(proposer.db, proposer.room(), proposer.publicKey, proposer.secret)
        harness.pump()
        val session = proposer.db.frostSigningSessionDao().getSessionsForChatRoom(harness.roomId).single()
        FrostSigningManager.approve(device(approver).db, device(approver).room(), session.id)
        harness.pump()
        assertEquals(InboxKeyState.ANNOUNCED, proposer.keys().single().state)
    }

    /** Every device takes its rotation pass, then everything is delivered. */
    private suspend fun passAndPump() {
        devices.values.forEach { InboxRotation.pass(it.db, it.publicKey, it.secret) }
        harness.pump()
    }

    private fun mailTo(key: HexKey, text: String): Event {
        val message = RumorAssembler.assembleRumor<Event>(zola.pubKey.toHexKey(), EventTemplate(now, 14, arrayOf(arrayOf("p", harness.roomId)), text))
        val seal = NostrSignerSync(zola).sign<SealEvent>(now, 13, emptyArray(),
            InboxWire.encrypt(InboxSeal.rumorJson(message), InboxWire.conversationKey(zola.privKey!!, key)))
        return InboxWrap.wrap(seal, harness.roomId, key)
    }

    @Test
    fun `removing an admin rotates the key with nobody asked, and the removed admin cannot read the new one`() = runBlocking<Unit> {
        open()
        announced()
        val alice = device("alice"); val bob = device("bob"); val carol = device("carol")

        // Carol stops being an admin. Her device lets go of the inbox; the others owe a rotation.
        harness.admins = setOf(alice.publicKey, bob.publicKey)
        GroupInboxManager.forgetIfNotAdmin(carol.db, harness.roomId, carol.publicKey, stillAdmin = false)
        assertTrue(InboxRotation.isOwed(alice.db, alice.room(), alice.publicKey))
        assertEquals(1, listOf(alice, bob).count { InboxRotation.startsIt(it.db, harness.roomId, it.publicKey) }, "one admin starts it")

        passAndPump()

        listOf(alice, bob).forEach { admin ->
            val keys = admin.keys()
            assertEquals(listOf(1, 0), keys.map { it.generation }, "${admin.name} holds both")
            assertEquals(InboxKeyState.ANNOUNCED, keys[0].state, "${admin.name}'s device signed the new announcement unasked")
            assertEquals(InboxKeyState.RETIRED, keys[1].state)
            assertFalse(InboxRotation.isOwed(admin.db, admin.room(), admin.publicKey))
        }
        assertTrue(carol.keys().all { it.secretKey == null }, "Carol's device kept nothing")

        val newKey = alice.keys()[0].publicKey
        val oldKey = alice.keys()[1].publicKey
        assertNotNull(GroupInboxManager.receive(bob.db, mailTo(newKey, "To the new key"), bob.publicKey))
        assertNotNull(GroupInboxManager.receive(bob.db, mailTo(oldKey, "To the old key"), bob.publicKey), "old mail still opens")
        assertNull(GroupInboxManager.receive(carol.db, mailTo(newKey, "Not for Carol"), carol.publicKey))
    }

    @Test
    fun `a promoted admin holds only the new key`() = runBlocking<Unit> {
        open()
        val alice = device("alice"); val bob = device("bob"); val carol = device("carol")
        harness.admins = setOf(alice.publicKey, bob.publicKey)
        announced()
        assertEquals(emptyList(), carol.keys())

        harness.admins = setOf(alice.publicKey, bob.publicKey, carol.publicKey)
        passAndPump()

        assertEquals(listOf(1), carol.keys().map { it.generation }, "nothing from before")
        assertEquals(alice.keys().first().publicKey, carol.keys().single().publicKey)
        assertEquals(InboxKeyState.ANNOUNCED, carol.keys().single().state)
    }

    @Test
    fun `a reply proposed before a rotation still goes out after it`() = runBlocking<Unit> {
        open()
        announced()
        val alice = device("alice"); val bob = device("bob")
        val firstKey = alice.keys().single().publicKey
        assertNotNull(GroupInboxManager.receive(alice.db, mailTo(firstKey, "Hello"), alice.publicKey))
        val conversation = alice.db.groupInboxDao().getMessages(harness.roomId).single().conversationId
        val reply = InboxReplies.propose(alice.db, alice.room(), alice.publicKey, conversation, "Sealed before the change").id
        harness.pump()

        InboxKeyRitualManager.propose(bob.db, bob.room(), bob.publicKey, bob.secret)
        harness.pump()
        assertEquals(InboxKeyState.ANNOUNCED, alice.keys().first { it.generation == 1 }.state)

        FrostSigningManager.approve(bob.db, bob.room(), reply)
        harness.pump()
        assertEquals(FrostSigningStage.COMPLETE, alice.db.frostSigningSessionDao().getSessionById(reply)?.stage)
        assertEquals(2, alice.db.groupInboxDao().getPublicationsFor(reply).size, "Zola's wrap and the group's copy")
    }

    @Test
    fun `two rotations started at once end as one, on every device`() = runBlocking<Unit> {
        open()
        announced()
        val alice = device("alice"); val bob = device("bob"); val carol = device("carol")
        val first = InboxKeyRitualManager.propose(alice.db, alice.room(), alice.publicKey, alice.secret)
        val second = InboxKeyRitualManager.propose(bob.db, bob.room(), bob.publicKey, bob.secret)
        harness.pump()

        val (winner, loser) = if (first.id < second.id) first to second else second to first
        listOf(alice, bob, carol).forEach { admin ->
            assertEquals(InboxKeyCeremonyStage.COMPLETE, admin.ceremony(winner.id)?.stage, "${admin.name} finished one")
            assertEquals(InboxKeyCeremonyStage.FAILED, admin.ceremony(loser.id)?.stage, "${admin.name} ended the other")
            assertEquals(listOf(1, 0), admin.keys().map { it.generation })
        }
        assertEquals(1, listOf(alice, bob, carol).map { it.keys().first().publicKey }.distinct().size, "one key for everyone")
    }

    @Test
    fun `nothing is owed while the admins are the ones who made the key`() = runBlocking<Unit> {
        open()
        announced()
        devices.values.forEach { assertFalse(InboxRotation.isOwed(it.db, it.room(), it.publicKey)) }
        devices.values.forEach { assertEquals(0, InboxRotation.pass(it.db, it.publicKey, it.secret)) }
    }
}
