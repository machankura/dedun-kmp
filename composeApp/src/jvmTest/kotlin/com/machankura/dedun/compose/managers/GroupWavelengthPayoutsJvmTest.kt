package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.model.GroupArkPayment
import com.machankura.dedun.compose.database.model.GroupArkVtxo
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthExitEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthPaymentEvent
import com.machankura.dedun.compose.text.ProposedEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Feature
import fr.acinq.lightning.FeatureSupport
import fr.acinq.lightning.Features
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.utils.msat
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/** The Wavelength wallet pays only its members, from what they put in. Until this rule, a quorum could approve paying anyone. */
class GroupWavelengthPayoutsJvmTest {
    private val fixture = GroupLightningFixture()
    private val now get() = Clock.System.now().epochSeconds
    private val key get() = GroupArkCoSigner.walletKey(fixture.roomId, fixture.chain, 0)

    @AfterTest
    fun close() = fixture.close()

    private suspend fun opened(): Pair<List<GroupLightningFixture.Device>, GroupWavelengthWalletManager.Wallet> {
        val all = fixture.group()
        fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_WAVELENGTH)
        val session = GroupWavelengthWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, fixture.chain, "02" + "ab".repeat(32))
        fixture.signSession(all, all[0], session.id)
        return all to assertNotNull(GroupWavelengthWalletManager.current(all[0].db, fixture.roomId, fixture.chain))
    }

    private fun invoice(sat: Long): String = Bolt11Invoice.create(
        fixture.chain, (sat * 1000).msat, randomBytes32(), PrivateKey(ByteVector32("11".repeat(32))), Either.Left("pay"), CltvExpiryDelta(18),
        Features(Feature.VariableLengthOnion to FeatureSupport.Mandatory, Feature.PaymentSecret to FeatureSupport.Mandatory), randomBytes32(),
    ).write()

    private fun proposed(payment: GroupWavelengthPaymentEvent.Payment): Event {
        val template = GroupWavelengthPaymentEvent.template(payment)
        return Event("00".repeat(32), "00".repeat(32), 0, template.kind, template.tags, template.content, "")
    }

    private fun payment(sat: Long, payout: GroupWavelengthPaymentEvent.Payout?, expiresAt: Long = 4_000_000_000) =
        GroupWavelengthPaymentEvent.Payment(fixture.chain, 0, invoice(sat), 50, 500, expiresAt, payout)

    private suspend fun refused(device: GroupLightningFixture.Device, payment: GroupWavelengthPaymentEvent.Payment) =
        GroupWavelengthPayouts.refuse(device.db, fixture.roomId, proposed(payment))

    @Test
    fun `a payment that names nobody isn't signed, however many would like it to be`() = runBlocking<Unit> {
        val (all, _) = opened()
        assertEquals("pays somebody it does not name as a member", refused(all[0], payment(1_000, null)))
    }

    @Test
    fun `nor one to a member who's given nowhere to be paid, or somewhere they've since replaced`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        assertEquals("pays a member who has given nowhere to be paid", refused(all[0], payment(1_000, GroupWavelengthPaymentEvent.Payout(all[1].publicKey, "d"))))
        fixture.payable(all, all[1], wallet, 10_000)
        assertEquals("pays a destination the member has since replaced", refused(all[0], payment(1_000, GroupWavelengthPaymentEvent.Payout(all[1].publicKey, "old"))))
    }

    @Test
    fun `a member can be paid what they put in, fee limit included, and no more`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val payout = fixture.payable(all, all[1], wallet, 10_000)
        assertNull(refused(all[0], payment(9_950, payout)))
        assertTrue("more than the 10000 sats" in refused(all[0], payment(9_951, payout)).orEmpty())
    }

    @Test
    fun `someone else's balance isn't theirs to spend`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        fixture.payable(all, all[1], wallet, 10_000)
        val poorer = fixture.payable(all, all[2], wallet, 1_000)
        assertTrue("more than" in refused(all[0], payment(5_000, poorer)).orEmpty())
    }

    @Test
    fun `what the wallet has lost in fees falls on everybody in proportion`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        fixture.payable(all, all[1], wallet, 30_000)
        fixture.payable(all, all[2], wallet, 10_000)
        // 40,000 went in, and rounds have taken a quarter.
        val dao = all[0].db.groupArkDao()
        dao.upsertVtxo(GroupArkVtxo(key, "coin1:0", 20_000, settled = true))
        dao.upsertVtxo(GroupArkVtxo(key, "coin2:0", 10_000, settled = true))
        val balances = GroupWavelengthPayouts.balancesIn(all[0].db, fixture.roomId, fixture.chain)
        assertEquals(22_500, balances.getValue(all[1].publicKey.lowercase()).availableSat)
        assertEquals(7_500, balances.getValue(all[2].publicKey.lowercase()).availableSat)
    }

    @Test
    fun `a device with no record of the coins won't sign a payout it can't check`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val payout = fixture.payable(all, all[1], wallet, 10_000)
        val dao = all[0].db.groupArkDao()
        dao.upsertVtxo(GroupArkVtxo(key, "coin1:0", 10_000, settled = true, spent = true))
        assertTrue("more than the 0 sats" in refused(all[0], payment(1_000, payout)).orEmpty())
    }

    @Test
    fun `an approval counts until it expires, and a sent payment counts for good`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val payout = fixture.payable(all, all[1], wallet, 10_000)
        val member = all[1].publicKey.lowercase()
        val approved = payment(4_000, payout, expiresAt = now + 100)
        fixture.fileSignedAsRoom(all, GroupWavelengthPaymentEvent.KIND, GroupWavelengthPaymentEvent.template(approved).tags, "", createdAt = now)
        assertEquals(5_950, GroupWavelengthPayouts.balancesIn(all[0].db, fixture.roomId, fixture.chain, now).getValue(member).availableSat)
        assertEquals(10_000, GroupWavelengthPayouts.balancesIn(all[0].db, fixture.roomId, fixture.chain, now + 200).getValue(member).availableSat, "an expired one is released")

        all[0].db.groupArkDao().upsertPayment(GroupArkPayment(key, approved.paymentHash!!.toString(), "ark"))
        val sent = GroupWavelengthPayouts.balancesIn(all[0].db, fixture.roomId, fixture.chain, now + 200).getValue(member)
        assertEquals(4_050, sent.paidSat)
        assertEquals(5_950, sent.availableSat, "and doesn't come back when the approval ends")
    }

    @Test
    fun `a signed payment naming nobody pays nothing, on any device`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        fixture.fileSignedAsRoom(all, GroupWavelengthPaymentEvent.KIND, GroupWavelengthPaymentEvent.template(payment(1_000, null)).tags, "", createdAt = now)
        assertTrue(GroupWavelengthPayments.approved(all[0].db, fixture.roomId, wallet).isEmpty())
    }

    @Test
    fun `a request to leave isn't a payment, and a quorum can sign it naming nobody`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val template = GroupWavelengthExitEvent.template(GroupWavelengthExitEvent.asking(fixture.chain, 0))
        val event = Event("00".repeat(32), "00".repeat(32), 0, template.kind, template.tags, template.content, "")
        assertNull(GroupWavelengthPayouts.refuse(all[0].db, fixture.roomId, event))
        assertNull(GroupWavelengthPaymentEvent.read(event.tags), "and nobody reads it as one")
        val asking = GroupWavelengthPayouts.proposeExit(all[1].db, all[1].room, all[1].publicKey, wallet)
        fixture.signSession(all, all[1], asking.id)
        all.forEach { assertEquals(1, GroupWavelengthPayouts.signedExits(it.db, fixture.roomId, fixture.chain, 0).size, "device ${it.index}") }
        assertTrue(GroupWavelengthPayments.approved(all[0].db, fixture.roomId, wallet).isEmpty())
        assertEquals("Leave the Wavelength wallet", ProposedEvent.summarize(event).label)
    }
}
