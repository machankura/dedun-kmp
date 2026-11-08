package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.model.GroupArkArriving
import com.machankura.dedun.compose.database.model.GroupArkLeave
import com.machankura.dedun.compose.database.model.GroupArkPayment
import com.machankura.dedun.compose.database.model.GroupArkSnapshotRow
import com.machankura.dedun.compose.network.serialization.CommonJson
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthSnapshot
import com.machankura.dedun.compose.database.model.GroupArkVtxo
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthExitEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthOnchainPayoutEvent
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
import kotlin.time.Instant

/** The Wavelength wallet pays only its members, from what they put in. Until this rule, a quorum could approve paying anyone. */
class GroupWavelengthPayoutsJvmTest {
    private val fixture = GroupLightningFixture()
    private val now get() = Clock.System.now().epochSeconds
    private val key get() = GroupArkCoSigner.walletKey(fixture.roomId, fixture.chain, 0)

    @AfterTest
    fun close() = fixture.close()

    private suspend fun opened(): Pair<List<GroupLightningFixture.Device>, GroupWavelengthWalletManager.Wallet> {
        val all = fixture.group()
        devices = all
        fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_WAVELENGTH)
        val session = GroupWavelengthWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, fixture.chain, "02" + "ab".repeat(32))
        fixture.signSession(all, all[0], session.id)
        return all to assertNotNull(GroupWavelengthWalletManager.current(all[0].db, fixture.roomId, fixture.chain))
    }

    /** Every device of the group in the test under way, whose Lightning addresses an invoice's metadata names. */
    private var devices: List<GroupLightningFixture.Device> = emptyList()

    private fun metadataOf(member: String?): String? = member?.let { fixture.metadataOf(devices, it) }

    private fun invoice(sat: Long, metadata: String? = null): String = Bolt11Invoice.create(
        fixture.chain, (sat * 1000).msat, randomBytes32(), PrivateKey(ByteVector32("11".repeat(32))),
        metadata?.let { Either.Right(ByteVector32(fr.acinq.bitcoin.Crypto.sha256(it.encodeToByteArray()))) } ?: Either.Left("pay"), CltvExpiryDelta(18),
        Features(Feature.VariableLengthOnion to FeatureSupport.Mandatory, Feature.PaymentSecret to FeatureSupport.Mandatory), randomBytes32(),
    ).write()

    private fun proposed(payment: GroupWavelengthPaymentEvent.Payment): Event {
        val template = GroupWavelengthPaymentEvent.template(payment)
        return Event("00".repeat(32), "00".repeat(32), 0, template.kind, template.tags, template.content, "")
    }

    private fun payment(sat: Long, payout: GroupWavelengthPaymentEvent.Payout?, expiresAt: Long = now + 3600, lnurl: String? = metadataOf(payout?.member)) =
        GroupWavelengthPaymentEvent.Payment(fixture.chain, 0, invoice(sat, lnurl), 50, 500, expiresAt, payout, lnurl)

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

    /**
     * The guard that keeps `docs/group-wavelength-unconfirmed-deposits.md`'s A2 from being undone.
     *
     * A boarding payment this device has seen on the chain is not a deposit, and the thing that would make
     * it one is a single helpful call to `DepositLedger.merge`. `balancesIn` reads the deposit ledger with
     * no filter on confirmation, so such a row is a payable balance the moment it exists -- and because the
     * share each member can take is scaled by `inTheWallet / totalNet`, one 50,000-sat payment that is
     * replaced before it confirms would halve every other member's payable balance, for free, and for ever,
     * since the ledger is kept rather than derived.
     *
     * So: the arriving table may hold anything at all, and every member's figures must not move.
     */
    @Test
    fun `a payment on its way in changes nobody's payable balance`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        fixture.payable(all, all[1], wallet, 30_000)
        fixture.payable(all, all[2], wallet, 10_000)
        val db = all[0].db
        val before = GroupWavelengthPayouts.balancesIn(db, fixture.roomId, fixture.chain, 1_800_000_000)
        val talliedBefore = DepositLedger.tallies(db, fixture.roomId, fixture.chain)

        // One member's 50,000 sats in the mempool, another's confirmed, and a third coin in a round this
        // device never signed: every shape the watch can write.
        val dao = db.groupArkDao()
        dao.upsertArriving(GroupArkArriving(key, "mempool:0", 50_000, "0014aa", all[1].publicKey.lowercase(), firstSeenAtEpochSeconds = 1))
        dao.upsertArriving(GroupArkArriving(key, "confirmed:0", 25_000, "0014bb", all[2].publicKey.lowercase(), firstSeenAtEpochSeconds = 2, blockHeight = 900))
        dao.upsertArriving(GroupArkArriving(key, "gone:0", 90_000, "0014cc", all[1].publicKey.lowercase(), firstSeenAtEpochSeconds = 3, blockHeight = 890, spentUnseenAtEpochSeconds = 4))

        assertEquals(before, GroupWavelengthPayouts.balancesIn(db, fixture.roomId, fixture.chain, 1_800_000_000))
        assertEquals(talliedBefore, DepositLedger.tallies(db, fixture.roomId, fixture.chain))
        assertEquals(3, dao.arriving(key).size, "and the rows really were there while that was checked")
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
    fun `a payment the operator says failed before this device signed anything frees the balance, and is never signed after`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val payout = fixture.payable(all, all[1], wallet, 10_000)
        val member = all[1].publicKey.lowercase()
        val approved = payment(4_000, payout)
        fixture.fileSignedAsRoom(all, GroupWavelengthPaymentEvent.KIND, GroupWavelengthPaymentEvent.template(approved).tags, "", createdAt = now)
        val hash = approved.paymentHash!!.toString()
        assertEquals(5_950, GroupWavelengthPayouts.balancesIn(all[0].db, fixture.roomId, fixture.chain).getValue(member).availableSat)
        assertTrue(approved.paymentHash in GroupWavelengthPayments.approved(all[0].db, fixture.roomId, wallet))

        // The change it would have left was under the operator's minimum, so it never reached a signer.
        report(all[0], GroupWavelengthSnapshot.Line("a1", "send", "failed", 4_005, failure = "change 12 is below minimum change amount 1000", paymentHash = hash))
        assertTrue(GroupWavelengthPayouts.wentNowhere(all[0].db, fixture.roomId, approved))
        assertEquals(10_000, GroupWavelengthPayouts.balancesIn(all[0].db, fixture.roomId, fixture.chain).getValue(member).availableSat)
        assertTrue(GroupWavelengthPayments.approved(all[0].db, fixture.roomId, wallet).isEmpty(), "whatever the operator asks after")

        // Where this device did sign its transfer, the operator's word that it failed changes nothing: only a settled refund does.
        all[0].db.groupArkDao().upsertPayment(GroupArkPayment(key, hash, "ark", 4_005))
        assertEquals(false, GroupWavelengthPayouts.wentNowhere(all[0].db, fixture.roomId, approved))
        assertEquals(4_005, GroupWavelengthPayouts.balancesIn(all[0].db, fixture.roomId, fixture.chain).getValue(member).paidSat)
    }

    private suspend fun report(device: GroupLightningFixture.Device, vararg lines: GroupWavelengthSnapshot.Line) {
        val snapshot = GroupWavelengthSnapshot(wallet = key, term = 1, sentAt = now, tip = 0, confirmedSat = 0, activity = lines.toList())
        device.db.groupArkDao().upsertSnapshot(GroupArkSnapshotRow(key, device.publicKey, 1, CommonJson.encodeToString(GroupWavelengthSnapshot.serializer(), snapshot), Instant.fromEpochSeconds(now)))
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

    @Test
    fun `an amount past all the bitcoin there is isn't a payment, and can't come out negative`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val payout = fixture.payable(all, all[1], wallet, 10_000)
        val hugeFee = GroupWavelengthPaymentEvent.template(GroupWavelengthPaymentEvent.Payment(fixture.chain, 0, invoice(1_000), Long.MAX_VALUE, 500, now + 100, payout))
        assertNull(GroupWavelengthPaymentEvent.read(hugeFee.tags), "a fee limit that would overflow")
        assertEquals("isn't a payment this device can read", GroupWavelengthPayouts.refuse(all[0].db, fixture.roomId, Event("00".repeat(32), "00".repeat(32), 0, hugeFee.kind, hugeFee.tags, "", "")))
        // 100,000,000 whole bitcoin, as an invoice can say and a Long multiplied by it can't hold.
        val decoded = GroupWavelengthPaymentEvent.Payment(fixture.chain, 0, invoice(1_000).replace("10u", "100000000"), 0, 0, 0)
        assertNull(decoded.amountSat)
    }

    @Test
    fun `a second approval for a hash isn't signed, and whichever was first is what's paid and charged`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val payout = fixture.payable(all, all[1], wallet, 10_000)
        val first = payment(1_000, payout)
        fixture.fileSignedAsRoom(all, GroupWavelengthPaymentEvent.KIND, GroupWavelengthPaymentEvent.template(first).tags, "", createdAt = now)
        val again = first.copy(maxFeeSat = 5_000)
        assertEquals("pays a hash the group has already approved", refused(all[0], again))
        fixture.fileSignedAsRoom(all, GroupWavelengthPaymentEvent.KIND, GroupWavelengthPaymentEvent.template(again).tags, "", createdAt = now + 1)
        assertEquals(first.maxSat, GroupWavelengthPayments.approved(all[0].db, fixture.roomId, wallet)[first.paymentHash!!]?.maxSat)
        assertEquals(first.maxSat, GroupWavelengthPayouts.balancesIn(all[0].db, fixture.roomId, fixture.chain).getValue(all[1].publicKey.lowercase()).openSat)
    }

    @Test
    fun `an approval can't stand for ever, and nor can a request to leave`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val payout = fixture.payable(all, all[1], wallet, 10_000)
        assertTrue("longer than" in refused(all[0], payment(1_000, payout, expiresAt = 4_000_000_000)).orEmpty())
        val forever = GroupWavelengthExitEvent.template(GroupWavelengthExitEvent.Exit(fixture.chain, 0, 4_000_000_000, now))
        assertTrue("longer than" in GroupWavelengthPayouts.refuse(all[0].db, fixture.roomId, Event("00".repeat(32), "00".repeat(32), 0, forever.kind, forever.tags, "", "")).orEmpty())
    }

    @Test
    fun `two proposals being signed at once can't each pass against one balance, and approving looks again`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val payout = fixture.payable(all, all[1], wallet, 10_000)
        val first = GroupWavelengthPayments.propose(all[1].db, all[1].room, all[1].publicKey, wallet, invoice(6_000, metadataOf(payout.member)), 50, 500, now + 3600, payout, metadataOf(payout.member))
        fixture.pump(all, com.machankura.dedun.compose.nostr.frost.FrostSigningEvents.ALL)
        assertNotNull(all[2].db.frostSigningSessionDao().getSessionById(first.id), "the first is in")
        assertEquals("pays a member who already has a payout waiting", refused(all[2], payment(6_000, payout)))
        assertTrue(runCatching { GroupWavelengthPayments.propose(all[1].db, all[1].room, all[1].publicKey, wallet, invoice(6_000, metadataOf(payout.member)), 50, 500, now + 3600, payout, metadataOf(payout.member)) }.isFailure, "nor on the proposer's own device")
    }

    // ---- onchain, as a leave in a round: docs/group-wavelength-payouts.md, W2 to W11 ----

    private val aliceAddress = "bcrt1qzyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3lgth6c"
    private val bobAddress = "bcrt1qyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zs4w3j0"

    private fun onchain(payout: GroupWavelengthPaymentEvent.Payout, amountSat: Long, address: String = aliceAddress, maxFeeSat: Long = 1_000, at: Long = now) =
        GroupWavelengthOnchainPayoutEvent.paying(fixture.chain, 0, payout.member, payout.destinationEventId, address, amountSat, maxFeeSat, at)

    private fun proposed(payout: GroupWavelengthOnchainPayoutEvent.Payout): Event {
        val template = GroupWavelengthOnchainPayoutEvent.template(payout)
        return Event("00".repeat(32), "00".repeat(32), 0, template.kind, template.tags, template.content, "")
    }

    private suspend fun refused(device: GroupLightningFixture.Device, payout: GroupWavelengthOnchainPayoutEvent.Payout) =
        GroupWavelengthPayouts.refuse(device.db, fixture.roomId, proposed(payout))

    @Test
    fun `an onchain payout reads back as itself, and as nothing older devices know`() {
        val payout = GroupWavelengthOnchainPayoutEvent.paying(fixture.chain, 3, "AB".repeat(32), "d", aliceAddress, 9_000, 1_000, 1_700_000_000)
        val tags = GroupWavelengthOnchainPayoutEvent.template(payout).tags
        assertEquals(payout.copy(member = payout.member.lowercase()), GroupWavelengthOnchainPayoutEvent.read(tags))
        assertNull(GroupWavelengthPaymentEvent.read(tags), "a device that knows only Lightning payments refuses it")
        assertNull(GroupWavelengthExitEvent.read(tags), "and doesn't take it for leaving")
        assertNull(GroupWavelengthOnchainPayoutEvent.read(GroupWavelengthOnchainPayoutEvent.template(payout.copy(address = "nonsense")).tags), "an address that isn't one")
        assertNull(GroupWavelengthOnchainPayoutEvent.read(GroupWavelengthOnchainPayoutEvent.template(payout.copy(address = "bc1qzyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3h8ffkz")).tags), "nor one on another chain")
        assertNull(GroupWavelengthOnchainPayoutEvent.read(GroupWavelengthOnchainPayoutEvent.template(payout.copy(amountSat = Long.MAX_VALUE)).tags), "nor more than there is")
        val other = tags.map { if (it[0] == "payout") arrayOf("payout", "cd".repeat(32), "d") else it }.toTypedArray()
        assertNull(GroupWavelengthOnchainPayoutEvent.read(other), "nor a label naming someone else than the payout does")
        assertTrue("Pay out @" in ProposedEvent.summarize(proposed(payout)).label)
        assertTrue(aliceAddress in ProposedEvent.summarize(proposed(payout)).detail)
    }

    @Test
    fun `a member is paid onchain what they put in, fee limit included, and no more`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val payout = fixture.payable(all, all[1], wallet, 10_000, onchainAddress = aliceAddress)
        assertNull(refused(all[0], onchain(payout, 9_000)))
        assertTrue("more than the 10000 sats" in refused(all[0], onchain(payout, 9_001)).orEmpty())
    }

    @Test
    fun `only to the address the member gave, and only one they alone gave`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val noAddress = fixture.payable(all, all[2], wallet, 10_000)
        assertEquals("pays a member who has given no onchain address", refused(all[0], onchain(noAddress, 5_000)))
        val payout = fixture.payable(all, all[1], wallet, 10_000, onchainAddress = aliceAddress)
        assertEquals("pays an address other than the one the member gave", refused(all[0], onchain(payout, 5_000, address = bobAddress)))
        assertEquals("pays a destination the member has since replaced", refused(all[0], onchain(payout.copy(destinationEventId = "old"), 5_000)))
        // Somebody else signs Alice's address as theirs: nobody can tell whose it is, so it's nobody's to be paid.
        fixture.payable(all, all[3], wallet, 1_000, onchainAddress = aliceAddress)
        assertEquals("pays an address more than one member gave", refused(all[0], onchain(payout, 5_000)))
    }

    @Test
    fun `an onchain payout carries at least what an output can, and stands no longer than anything else`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val payout = fixture.payable(all, all[1], wallet, 10_000, onchainAddress = aliceAddress)
        assertTrue("less than an output can carry" in refused(all[0], onchain(payout, 545)).orEmpty())
        assertTrue("longer than" in refused(all[0], onchain(payout, 5_000).copy(expiresAt = 4_000_000_000)).orEmpty())
    }

    @Test
    fun `one payout at a time, whichever rail it's on`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val payout = fixture.payable(all, all[1], wallet, 10_000, onchainAddress = aliceAddress)
        val lightning = GroupWavelengthPayments.propose(all[1].db, all[1].room, all[1].publicKey, wallet, invoice(1_000, metadataOf(payout.member)), 50, 500, now + 3600, payout, metadataOf(payout.member))
        fixture.pump(all, com.machankura.dedun.compose.nostr.frost.FrostSigningEvents.ALL)
        assertNotNull(all[2].db.frostSigningSessionDao().getSessionById(lightning.id))
        assertEquals("pays a member who already has a payout waiting", refused(all[2], onchain(payout, 1_000)))

        val (others, otherWallet) = all to wallet
        val bob = fixture.payable(others, others[2], otherWallet, 10_000, onchainAddress = bobAddress)
        val first = GroupWavelengthPayouts.proposeOnchain(others[2].db, others[2].room, others[2].publicKey, onchain(bob, 1_000, address = bobAddress))
        fixture.pump(others, com.machankura.dedun.compose.nostr.frost.FrostSigningEvents.ALL)
        assertNotNull(others[3].db.frostSigningSessionDao().getSessionById(first.id))
        assertEquals("pays a member who already has a payout waiting", refused(others[3], payment(1_000, bob)))
    }

    @Test
    fun `an onchain payout is charged while it stands, for what left once a round pays it, and not at all once it's gone unpaid`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val payout = fixture.payable(all, all[1], wallet, 10_000, onchainAddress = aliceAddress)
        val member = all[1].publicKey.lowercase()
        val approved = onchain(payout, 6_000, maxFeeSat = 1_000).copy(expiresAt = now + 100)
        fixture.fileSignedAsRoom(all, GroupWavelengthOnchainPayoutEvent.KIND, GroupWavelengthOnchainPayoutEvent.template(approved).tags, "", createdAt = now)
        fun balance(at: Long) = runBlocking { GroupWavelengthPayouts.balancesIn(all[0].db, fixture.roomId, fixture.chain, at).getValue(member) }
        assertEquals(7_000, balance(now).openSat)
        assertEquals(3_000, balance(now).availableSat)
        assertEquals(10_000, balance(now + 200).availableSat, "an expired one is released")

        // This device signs a round paying it, for a fee of 400: from then on that's what Alice has been paid.
        all[0].db.groupArkDao().upsertLeave(GroupArkLeave(key, approved.id, "commitment", "00", 6_000, 400))
        assertEquals(6_400, balance(now + 200).paidSat)
        assertEquals(0, balance(now).openSat)

        // The round is seen to die: nothing left, and the approval stands again while it lasts.
        GroupArkCoSigner.abandoned(all[0].db, key, "commitment")
        assertEquals(0, balance(now).paidSat)
        assertEquals(7_000, balance(now).openSat)
    }

    @Test
    fun `a co-signer is told what the group approved leaving, by script, and the round it already gave each to`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val payout = fixture.payable(all, all[1], wallet, 10_000, onchainAddress = aliceAddress)
        val session = GroupWavelengthPayouts.proposeOnchain(all[1].db, all[1].room, all[1].publicKey, onchain(payout, 6_000))
        fixture.signSession(all, all[1], session.id)
        val approved = assertNotNull(GroupWavelengthPayouts.signedOnchain(all[0].db, fixture.roomId, fixture.chain).singleOrNull())
        all.forEach { device ->
            val leaves = GroupWavelengthPayouts.approvedLeaves(device.db, fixture.roomId, wallet)
            assertEquals(com.machankura.dedun.compose.wallet.wavelength.group.ArkPolicy.AuthorisedLeave(approved.id, 6_000, 1_000), leaves[approved.script], "device ${device.index}")
        }
        assertTrue(GroupWavelengthPayouts.approvedLeaves(all[0].db, fixture.roomId, wallet, now = approved.expiresAt + 1).isEmpty(), "an expired one leaves nothing")
        all[0].db.groupArkDao().upsertLeave(GroupArkLeave(key, approved.id, "commitment", "00", 6_000, 400))
        assertEquals(1, GroupWavelengthPayouts.approvedLeaves(all[0].db, fixture.roomId, wallet, now = approved.expiresAt + 1).size, "unless a round already has it, which may still need signing")
        assertEquals(mapOf(approved.id to "commitment"), GroupArkCoSigner.contextOf(all[0].db, fixture.roomId, wallet, key, 0).leftIn)
    }

    // ---- Lightning, level with the Lightning wallet's: docs/group-wavelength-payouts.md, Phase 5 ----

    @Test
    fun `a Lightning payout's invoice has to commit to metadata naming the member's address`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val payout = fixture.payable(all, all[1], wallet, 10_000)
        assertNull(refused(all[0], payment(1_000, payout)))
        assertEquals("carries no metadata to check the member's Lightning address against", refused(all[0], payment(1_000, payout, lnurl = null)))
        // Metadata naming somebody else, in an invoice that commits to it.
        assertEquals("carries metadata that does not name the member's Lightning address", refused(all[0], payment(1_000, payout, lnurl = fixture.metadataOf(all[2]))))
        // The member's metadata beside an invoice that commits to something else.
        val other = GroupWavelengthPaymentEvent.Payment(fixture.chain, 0, invoice(1_000, fixture.metadataOf(all[2])), 50, 500, now + 3600, payout, fixture.metadataOf(all[1]))
        assertEquals("carries an invoice that does not commit to the metadata beside it", refused(all[0], other))
    }

    @Test
    fun `a payout sent is charged what its vHTLC held, not its limit`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val payout = fixture.payable(all, all[1], wallet, 10_000)
        val approved = payment(4_000, payout)
        fixture.fileSignedAsRoom(all, GroupWavelengthPaymentEvent.KIND, GroupWavelengthPaymentEvent.template(approved).tags, "", createdAt = now)
        all[0].db.groupArkDao().insertPayment(GroupArkPayment(key, approved.paymentHash!!.toString(), "ark", amountSat = 4_012, vhtlcScript = "51"))
        val balance = GroupWavelengthPayouts.balancesIn(all[0].db, fixture.roomId, fixture.chain).getValue(all[1].publicKey.lowercase())
        assertEquals(4_012, balance.paidSat)
        assertEquals(5_988, balance.availableSat)
    }

    @Test
    fun `a refund gives the member their balance back once it has settled, and not before`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val payout = fixture.payable(all, all[1], wallet, 10_000)
        val member = all[1].publicKey.lowercase()
        val approved = payment(4_000, payout)
        fixture.fileSignedAsRoom(all, GroupWavelengthPaymentEvent.KIND, GroupWavelengthPaymentEvent.template(approved).tags, "", createdAt = now)
        val dao = all[0].db.groupArkDao()
        dao.insertPayment(GroupArkPayment(key, approved.paymentHash!!.toString(), "ark", amountSat = 4_012, vhtlcScript = "51"))
        suspend fun paid() = GroupWavelengthPayouts.balancesIn(all[0].db, fixture.roomId, fixture.chain).getValue(member).paidSat

        // A refund of some other vHTLC is nothing to do with it.
        dao.refundPayment(key, "52", "elsewhere")
        assertEquals(4_012, paid())
        // Its own, signed and not yet settled, changes nothing either: a transfer can be signed and never happen.
        dao.refundPayment(key, "51", "refund")
        dao.upsertVtxo(GroupArkVtxo(key, "refund:0", 4_012, origin = "refund"))
        assertEquals(4_012, paid())
        dao.settle(key, "refund")
        assertEquals(0, paid(), "the money came home")
        // A record of the payment again, as a repeated signature would make, doesn't undo it.
        dao.insertPayment(GroupArkPayment(key, approved.paymentHash!!.toString(), "ark", amountSat = 4_012, vhtlcScript = "51"))
        assertEquals(0, paid())
    }

    // ---- what the review found ----

    @Test
    fun `a device that saw a member paid won't pay a second approval of the same money, whoever approved it`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val payout = fixture.payable(all, all[1], wallet, 10_000, onchainAddress = aliceAddress)
        val first = onchain(payout, 6_000)
        fixture.fileSignedAsRoom(all, GroupWavelengthOnchainPayoutEvent.KIND, GroupWavelengthOnchainPayoutEvent.template(first).tags, "", createdAt = first.askedAt)
        val dao = all[0].db.groupArkDao()
        dao.upsertLeave(GroupArkLeave(key, first.id, "round", "00", 6_000, 400))
        dao.confirmLeaves(key, "round", now)
        // A device that missed that round would approve this, and with the member's own device that's a quorum of two.
        val second = onchain(payout, 3_000, at = now + 1)
        fixture.fileSignedAsRoom(all, GroupWavelengthOnchainPayoutEvent.KIND, GroupWavelengthOnchainPayoutEvent.template(second).tags, "", createdAt = second.askedAt)
        assertTrue(GroupWavelengthPayouts.approvedLeaves(all[0].db, fixture.roomId, wallet).isEmpty(), "3,600 is left, and 4,000 is asked")
        val fits = onchain(payout, 2_000, at = now + 2)
        fixture.fileSignedAsRoom(all, GroupWavelengthOnchainPayoutEvent.KIND, GroupWavelengthOnchainPayoutEvent.template(fits).tags, "", createdAt = fits.askedAt)
        assertTrue(GroupWavelengthPayouts.approvedLeaves(all[0].db, fixture.roomId, wallet).isEmpty(), "nor what fits beside it, while both stand")
        // Once the one that doesn't fit has run out, the one that does is paid.
        assertEquals(listOf(fits.id), GroupWavelengthPayouts.approvedLeaves(all[0].db, fixture.roomId, wallet, now = second.expiresAt).values.map { it.id })
    }

    @Test
    fun `nor a Lightning approval the member's balance can't bear on this device`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val payout = fixture.payable(all, all[1], wallet, 10_000)
        val sent = payment(6_000, payout)
        fixture.fileSignedAsRoom(all, GroupWavelengthPaymentEvent.KIND, GroupWavelengthPaymentEvent.template(sent).tags, "", createdAt = now)
        all[0].db.groupArkDao().insertPayment(GroupArkPayment(key, sent.paymentHash!!.toString(), "ark", amountSat = 6_010))
        val again = payment(5_000, payout)
        fixture.fileSignedAsRoom(all, GroupWavelengthPaymentEvent.KIND, GroupWavelengthPaymentEvent.template(again).tags, "", createdAt = now + 1)
        assertNull(GroupWavelengthPayments.approved(all[0].db, fixture.roomId, wallet)[again.paymentHash])
    }

    @Test
    fun `a paid payout doesn't stand in for a later one to the same address, and one still being paid holds the next back`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val payout = fixture.payable(all, all[1], wallet, 20_000, onchainAddress = aliceAddress)
        val first = onchain(payout, 6_000)
        fixture.fileSignedAsRoom(all, GroupWavelengthOnchainPayoutEvent.KIND, GroupWavelengthOnchainPayoutEvent.template(first).tags, "", createdAt = first.askedAt)
        val dao = all[0].db.groupArkDao()
        dao.upsertLeave(GroupArkLeave(key, first.id, "round", "00", 6_000, 400))
        assertEquals("pays an address another payout is still being paid to", refused(all[0], onchain(payout, 1_000, at = now + 1)))
        dao.confirmLeaves(key, "round", now)
        val later = onchain(payout, 9_000, at = now + 2)
        assertNull(refused(all[0], later))
        fixture.fileSignedAsRoom(all, GroupWavelengthOnchainPayoutEvent.KIND, GroupWavelengthOnchainPayoutEvent.template(later).tags, "", createdAt = later.askedAt)
        assertEquals(listOf(later.id), GroupWavelengthPayouts.approvedLeaves(all[0].db, fixture.roomId, wallet).values.map { it.id })
    }

    @Test
    fun `a refund that never happened gives way to the one that did`() = runBlocking<Unit> {
        val (all, wallet) = opened()
        val payout = fixture.payable(all, all[1], wallet, 10_000)
        val approved = payment(4_000, payout)
        fixture.fileSignedAsRoom(all, GroupWavelengthPaymentEvent.KIND, GroupWavelengthPaymentEvent.template(approved).tags, "", createdAt = now)
        val dao = all[0].db.groupArkDao()
        dao.insertPayment(GroupArkPayment(key, approved.paymentHash!!.toString(), "ark", amountSat = 4_012, vhtlcScript = "51"))
        dao.refundPayment(key, "51", "stalled")
        dao.refundPayment(key, "51", "real")
        dao.upsertVtxo(GroupArkVtxo(key, "real:0", 4_012, origin = "real", settled = true))
        assertEquals(0, GroupWavelengthPayouts.balancesIn(all[0].db, fixture.roomId, fixture.chain).getValue(all[1].publicKey.lowercase()).paidSat)
        dao.refundPayment(key, "51", "later")
        assertEquals("real", dao.payments(key).single().refundedIn, "and one that settled isn't written over")
    }
}
