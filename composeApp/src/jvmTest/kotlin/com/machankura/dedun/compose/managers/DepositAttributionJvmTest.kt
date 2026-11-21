package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.model.DepositRow
import com.machankura.dedun.compose.nostr.wallet.CoinAssignmentEvent
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent.Attribution
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent.DepositKey
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The group saying whose a deposit is -- `docs/member-deposits.md`, Phase 9: what every signer
 * checks before signing, and an attribution signed through the real signing manager, then
 * corrected, reaching every device's tallies.
 */
open class DepositAttributionJvmTest {
    private val fixture = GroupLightningFixture()

    @AfterTest
    fun close() = fixture.close()

    private val txid = "ab".repeat(32)
    private val legacy = DepositKey.Onchain(txid, 0)

    /** A deposit on the receive branch, nobody's, on every device: what a group had before deposits were attributed. */
    private suspend fun legacyDeposit(all: List<GroupLightningFixture.Device>, sats: Long = 50_000) = all.forEach { d ->
        d.db.depositDao().upsert(DepositRow(fixture.roomId, "regtest", legacy.text, DepositRow.ONCHAIN, sats * 1000))
    }

    /** An unsigned proposal of [attributions], as a signer reads it before the group has signed. */
    private fun proposal(vararg attributions: Attribution): Event {
        val template = DepositAttributionEvent.template(fixture.chain, attributions.toList(), 1_700_000_100)
        val id = EventHasher.hashId(fixture.roomId, 1_700_000_100, template.kind, template.tags, template.content)
        return Event(id, fixture.roomId, 1_700_000_100, template.kind, template.tags, template.content, "")
    }

    @Test
    fun `a signer refuses a deposit it has not seen, another amount, or somebody who is not a depositor`(): Unit = runBlocking {
        val all = fixture.group()
        fixture.openDeposits(all, all[1])
        legacyDeposit(all)
        val signer = all[2]
        val alice = all[1].publicKey

        assertNotNull(DepositLedger.refuseAttribution(signer.db, fixture.roomId, proposal(Attribution(DepositKey.Onchain("cd".repeat(32), 0), alice, 50_000))))
        assertNotNull(DepositLedger.refuseAttribution(signer.db, fixture.roomId, proposal(Attribution(legacy, alice, 49_000))))
        assertNotNull(DepositLedger.refuseAttribution(signer.db, fixture.roomId, proposal(Attribution(legacy, all[3].publicKey, 50_000))))
        assertNull(DepositLedger.refuseAttribution(signer.db, fixture.roomId, proposal(Attribution(legacy, alice, 50_000))))
        assertNull(DepositLedger.refuseAttribution(signer.db, fixture.roomId, proposal(Attribution(legacy, null, 50_000))), "the group's own")
    }

    @Test
    fun `an attribution the group signs reaches every device's tallies, and a later one corrects it`(): Unit = runBlocking {
        val all = fixture.group()
        fixture.openDeposits(all, all[1])
        fixture.openDeposits(all, all[2])
        legacyDeposit(all)
        all.forEach { assertEquals(50_000_000, DepositLedger.tallies(it.db, fixture.roomId, fixture.chain).unattributedMsat) }

        val first = DepositLedger.proposeAttribution(all[0].db, all[0].room, all[0].publicKey, fixture.chain, mapOf(legacy.text to all[1].publicKey))
        fixture.signSession(all, all[0], first.id)
        all.forEach { d ->
            val tallies = DepositLedger.tallies(d.db, fixture.roomId, fixture.chain)
            assertEquals(50_000_000, tallies.members.getValue(all[1].publicKey).onchainMsat, "device ${d.index}")
            assertEquals(0, tallies.unattributedMsat)
            assertEquals(DepositRow.BY_GROUP, DepositLedger.depositsIn(d.db, fixture.roomId, fixture.chain).single().attributedBy)
        }

        // A second, a moment later by the group's clock, corrects the first.
        kotlinx.coroutines.delay(1_100)
        val correction = DepositLedger.proposeAttribution(all[0].db, all[0].room, all[0].publicKey, fixture.chain, mapOf(legacy.text to all[2].publicKey))
        fixture.signSession(all, all[0], correction.id)
        all.forEach { d ->
            val tallies = DepositLedger.tallies(d.db, fixture.roomId, fixture.chain)
            assertEquals(50_000_000, tallies.members.getValue(all[2].publicKey).onchainMsat, "device ${d.index}")
            assertNull(tallies.members[all[1].publicKey], "device ${d.index}")
        }
    }

    // ---- After a share refresh (docs/giza-into-dedun.md, Phase 6) --------
    //
    // A refresh moves every share and leaves the key, so what the group signed with the old
    // shares and what it signs with the new ones are one signature to anybody reading them. These
    // are Dedun's own statements -- whose deposit it is, and whose a coin is -- signed after a
    // refresh and the wipe that deletes the ceremony's share.

    @Test
    fun `after a refresh and the wipe, an attribution signs at generation 1 and reaches every tally`(): Unit = runBlocking {
        val all = fixture.group()
        fixture.openDeposits(all, all[1])
        legacyDeposit(all)
        fixture.refreshOnchainKey(all)

        val session = DepositLedger.proposeAttribution(all[0].db, all[0].room, all[0].publicKey, fixture.chain, mapOf(legacy.text to all[1].publicKey))
        assertEquals(1, session.generation, "proposed after the refresh, it signs at the new generation")
        fixture.signSession(all, all[0], session.id)
        all.forEach { d ->
            assertEquals(50_000_000, DepositLedger.tallies(d.db, fixture.roomId, fixture.chain).members.getValue(all[1].publicKey).onchainMsat, "device ${d.index}")
        }
    }

    @Test
    fun `after a refresh and the wipe, a coin assignment signs at generation 1 and verifies as the room`(): Unit = runBlocking {
        val all = fixture.group()
        fixture.openDeposits(all, all[1])
        fixture.openDeposits(all, all[2])
        legacyDeposit(all)
        fixture.refreshOnchainKey(all)

        val coin = CoinAssignmentEvent.Coin(legacy, 50_000, mapOf(all[1].publicKey to 30_000, all[2].publicKey to 20_000))
        val session = CoinAssignments.propose(all[0].db, all[0].room, all[0].publicKey, fixture.chain, listOf(coin))
        assertEquals(1, session.generation)
        fixture.signSession(all, all[0], session.id)
        all.forEach { d ->
            val signed = d.db.groupSignedEventDao().getByChatRoomIdAndKind(fixture.roomId, CoinAssignmentEvent.KIND).single()
            assertTrue(signed.verifies(), "device ${d.index}: signed by the room, whose key the refresh left alone")
            assertEquals(listOf(coin), CoinAssignmentEvent.read(signed.toEvent())?.coins)
        }
    }
}
