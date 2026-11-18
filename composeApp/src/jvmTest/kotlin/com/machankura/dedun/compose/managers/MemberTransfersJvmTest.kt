package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.text.LightningAddress
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestinationEvent
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent
import com.machankura.dedun.compose.database.model.MarmotInnerEvent
import com.machankura.dedun.compose.database.model.DepositRow
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.nostr.frost.FrostSigningEvents
import com.machankura.dedun.compose.nostr.wallet.DepositAttributionEvent.DepositKey
import com.machankura.dedun.compose.nostr.wallet.MemberTransferEvent
import com.machankura.dedun.compose.nostr.wallet.TransferRequestEvent
import com.machankura.dedun.compose.nostr.wallet.TransferRequestEvent.Rail
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthPaymentEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.PrivateKey
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/**
 * Asking for, checking and signing a transfer: `docs/member-transfers.md`, Phase 3. One test per check in T4, each enough on
 * its own to refuse, and the whole way from a giver's request to every device's balances.
 */
class MemberTransfersJvmTest {
    private val fixture = GroupLightningFixture()
    private val now get() = Clock.System.now().epochSeconds

    @AfterTest
    fun close() = fixture.close()

    private lateinit var all: List<GroupLightningFixture.Device>
    private val alice get() = all[1]
    private val bob get() = all[2]
    private val carol get() = all[3]

    /** A Wavelength wallet, Alice with 30,000 sats in it, and Alice and Bob each a depositor. */
    private suspend fun opened(): GroupWavelengthWalletManager.Wallet {
        all = fixture.group()
        fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_WAVELENGTH)
        val session = GroupWavelengthWalletManager.proposeWallet(all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, fixture.chain, "02" + "ab".repeat(32))
        fixture.signSession(all, all[0], session.id)
        val wallet = assertNotNull(GroupWavelengthWalletManager.current(all[0].db, fixture.roomId, fixture.chain))
        fixture.openDeposits(all, alice)
        fixture.openDeposits(all, bob)
        fixture.payable(all, alice, wallet, 30_000)
        return wallet
    }

    private fun keyOf(device: GroupLightningFixture.Device) = PrivateKey(ByteVector32(device.privateKey))

    private fun request(
        from: GroupLightningFixture.Device = alice,
        to: String = bob.publicKey,
        sat: Long = 20_000,
        rail: Rail = Rail.WAVELENGTH,
        room: String = fixture.roomId,
        askedAt: Long = now,
        expiresAt: Long = askedAt + 86_400,
    ): TransferRequestEvent.Request =
        TransferRequestEvent.verified(TransferRequestEvent.build(keyOf(from), room, fixture.chain, rail, to, sat, expiresAt, null, askedAt)) as TransferRequestEvent.Request

    private fun proposed(request: TransferRequestEvent.Request): Event {
        val template = MemberTransferEvent.template(request)
        return Event("00".repeat(32), fixture.roomId, template.createdAt, template.kind, template.tags, template.content, "")
    }

    private suspend fun refused(request: TransferRequestEvent.Request, on: GroupLightningFixture.Device = carol) =
        MemberTransfers.refuse(on.db, fixture.roomId, proposed(request))

    private suspend fun wavelengthOf(device: GroupLightningFixture.Device, member: GroupLightningFixture.Device) =
        GroupWavelengthPayouts.balancesIn(device.db, fixture.roomId, fixture.chain)[member.publicKey.lowercase()]?.availableSat ?: 0

    // --- the whole way ---

    @Test
    fun `Alice asks, the group signs, and every device moves her balance to Bob`() = runBlocking<Unit> {
        opened()
        val (asked, session) = MemberTransfers.request(alice.db, alice.room, keyOf(alice), fixture.chain, Rail.WAVELENGTH, bob.publicKey, 20_000, "groceries")
        fixture.pump(all, setOf(TransferRequestEvent.KIND))
        fixture.signSession(all, alice, assertNotNull(session).id)

        all.forEach { device ->
            assertEquals(10_000, wavelengthOf(device, alice), "device ${device.index}")
            assertEquals(20_000, wavelengthOf(device, bob), "device ${device.index}")
            assertEquals(MemberTransfers.State.SIGNED, MemberTransfers.requestsIn(device.db, fixture.roomId).single { it.request.id == asked.id }.state)
        }
    }

    @Test
    fun `a member who holds no share asks, and another puts it to the group`() = runBlocking<Unit> {
        opened()
        // Carol's device puts Alice's signed request to the group, as a sponsor does (T10).
        val asked = request()
        val session = MemberTransfers.putToGroup(carol.db, carol.room, carol.publicKey, asked)
        assertEquals(session.id, MemberTransfers.putToGroup(carol.db, carol.room, carol.publicKey, asked).id, "and not twice")
        fixture.signSession(all, carol, session.id)
        val recorded = MemberTransfers.signedIn(bob.db, fixture.roomId, fixture.chain).single()
        assertEquals(alice.publicKey.lowercase(), recorded.from)
        assertEquals(alice.publicKey.lowercase(), recorded.request.event.pubKey.lowercase(), "signed by Alice herself")
    }

    @Test
    fun `asking for more than the giver has is refused on the giver's own device`() = runBlocking<Unit> {
        opened()
        assertFailsWith<IllegalArgumentException> {
            MemberTransfers.request(alice.db, alice.room, keyOf(alice), fixture.chain, Rail.WAVELENGTH, bob.publicKey, 30_001)
        }
        assertTrue(MemberTransfers.requestsIn(alice.db, fixture.roomId).isEmpty(), "and nothing was carried")
    }

    @Test
    fun `nothing is offered on mainnet yet, or onchain`() = runBlocking<Unit> {
        opened()
        assertFailsWith<IllegalArgumentException> {
            MemberTransfers.request(alice.db, alice.room, keyOf(alice), fixture.chain, Rail.ONCHAIN, bob.publicKey, 1_000)
        }
    }

    // --- T4, one check each ---

    @Test
    fun `a transfer fits what the giver has, and a sat more doesn't`() = runBlocking<Unit> {
        opened()
        assertNull(refused(request(sat = 30_000)))
        assertTrue("more than the 30000 sats" in refused(request(sat = 30_001)).orEmpty())
    }

    @Test
    fun `a quorum can't record a transfer the giver didn't sign`() = runBlocking<Unit> {
        opened()
        // Carol signs a request "from" herself and the proposal claims it is Alice's (fact 3).
        val carols = request(from = carol)
        val tags = MemberTransferEvent.template(carols).tags.map { if (it[0] == "from") arrayOf("from", alice.publicKey.lowercase()) else it }.toTypedArray()
        val forged = Event("00".repeat(32), fixture.roomId, now, MemberTransferEvent.KIND, tags, "", "")
        assertEquals("isn't a transfer its giver signed", MemberTransfers.refuse(bob.db, fixture.roomId, forged))
    }

    @Test
    fun `a request made in another group, expired, or standing too long is refused`() = runBlocking<Unit> {
        opened()
        assertEquals("is a transfer asked for in another group", refused(request(room = "ab".repeat(32))))
        assertEquals("was asked for in a request that has expired", refused(request(askedAt = now - 100, expiresAt = now - 1)))
        assertEquals("stands for longer than the group lets anything stand", refused(request(expiresAt = now + MemberTransfers.MAX_REQUEST_SECONDS + 1)))
    }

    @Test
    fun `a request its giver took back is refused, and declined at approval if it's already being signed`() = runBlocking<Unit> {
        opened()
        val asked = request()
        val session = MemberTransfers.putToGroup(carol.db, carol.room, carol.publicKey, asked)
        fixture.pump(all, FrostSigningEvents.ALL)
        MemberTransfers.withdraw(alice.db, alice.room, keyOf(alice), asked)
        fixture.pump(all, setOf(TransferRequestEvent.KIND))
        assertEquals("was taken back by the member who asked for it", refused(asked, on = bob))

        fixture.signSession(all, carol, session.id)
        assertTrue(MemberTransfers.signedIn(bob.db, fixture.roomId, fixture.chain).isEmpty(), "nobody signed it")
    }

    @Test
    fun `only its giver can take a request back`() = runBlocking<Unit> {
        opened()
        assertFailsWith<IllegalArgumentException> { MemberTransfers.withdraw(bob.db, bob.room, keyOf(bob), request()) }
    }

    @Test
    fun `taking back a request somebody else put to the group declines the giver's share of it`() = runBlocking<Unit> {
        opened()
        val asked = request()
        val session = MemberTransfers.putToGroup(carol.db, carol.room, carol.publicKey, asked)
        fixture.pump(all, FrostSigningEvents.ALL)
        MemberTransfers.withdraw(alice.db, alice.room, keyOf(alice), asked)
        assertNotNull(assertNotNull(alice.db.frostSigningSessionDao().getSessionById(session.id)).signDeclinedAt, "declined on the giver's device")
    }

    @Test
    fun `a request dated in the future, or a transfer dated outside its request's window, is refused`() = runBlocking<Unit> {
        opened()
        assertEquals("was asked for in a request dated in the future", refused(request(askedAt = now + 3_600)))
        val asked = request(askedAt = now - 100)
        val template = MemberTransferEvent.template(asked, createdAt = now - 200)
        val backdated = Event("00".repeat(32), fixture.roomId, template.createdAt, template.kind, template.tags, template.content, "")
        assertEquals("is dated outside its request's window", MemberTransfers.refuse(carol.db, fixture.roomId, backdated))
    }

    /** The review's second finding: a device that co-signed none of a payout's rounds can't take its expiry for "never paid". */
    @Test
    fun `a giver whose payout approval ran out here may have been paid it, and can't transfer it`() = runBlocking<Unit> {
        val wallet = opened()
        val payout = fixture.payable(all, alice, wallet, 30_000)
        val metadata = fixture.metadataOf(all, payout.member)
        val invoice = Bolt11Invoice.create(
            fixture.chain, 28_500_000.msat, randomBytes32(), PrivateKey(ByteVector32("11".repeat(32))),
            Either.Right(ByteVector32(Crypto.sha256(metadata.encodeToByteArray()))), CltvExpiryDelta(18),
            Features(Feature.VariableLengthOnion to FeatureSupport.Mandatory, Feature.PaymentSecret to FeatureSupport.Mandatory), randomBytes32(),
        ).write()
        val template = GroupWavelengthPaymentEvent.template(GroupWavelengthPaymentEvent.Payment(fixture.chain, 0, invoice, 500, 500, now - 1, payout, metadata))
        fixture.fileSignedAsRoom(all, template.kind, template.tags, template.content, createdAt = now - 3_600)

        // Her balance shows the money back, since the approval expired; what she can give is what the signed records cover.
        assertEquals(30_000, wavelengthOf(carol, alice))
        assertTrue("more than the 1000 sats" in refused(request(sat = 20_000)).orEmpty())
        assertNull(refused(request(sat = 1_000)))
    }

    @Test
    fun `a transfer batched with anything else is dropped before anybody sees it`() = runBlocking<Unit> {
        opened()
        val first = MemberTransferEvent.template(request(sat = 1_000, askedAt = now - 2))
        val second = MemberTransferEvent.template(request(sat = 1_000, askedAt = now - 1))
        val session = FrostSigningManager.proposeSigningBatch(carol.db, carol.room, carol.publicKey, listOf(first, second))
        fixture.pump(all, FrostSigningEvents.ALL)
        assertNull(bob.db.frostSigningSessionDao().getSessionById(session.id), "Bob's device dropped it")
    }

    @Test
    fun `a transfer to somebody outside the room, or to a member with no deposits opened, is refused`() = runBlocking<Unit> {
        opened()
        val stranger = PrivateKey(ByteVector32("77".repeat(32))).xOnlyPublicKey().value.toHex()
        assertEquals("is to somebody who isn't a member", refused(request(to = stranger)))
        assertEquals("is to a member the group hasn't opened deposits for", refused(request(to = carol.publicKey)))
    }

    @Test
    fun `a transfer from a member who has left is refused`() = runBlocking<Unit> {
        opened()
        fixture.remove(alice.publicKey, listOf(carol))
        assertEquals("is from somebody who isn't a member", refused(request()))
    }

    @Test
    fun `a request already recorded isn't recorded again`() = runBlocking<Unit> {
        opened()
        val asked = request()
        val session = MemberTransfers.putToGroup(carol.db, carol.room, carol.publicKey, asked)
        fixture.signSession(all, carol, session.id)
        assertEquals("is a transfer the group has already recorded", refused(asked, on = bob))
    }

    @Test
    fun `a giver with a payout waiting, or another transfer being signed, waits`() = runBlocking<Unit> {
        val wallet = opened()
        val second = request(sat = 1_000, askedAt = now - 5)
        MemberTransfers.putToGroup(carol.db, carol.room, carol.publicKey, second)
        assertEquals("is from a member who is already transferring part of their balance", refused(request(), on = carol))
        assertNull(refused(second, on = carol), "a session over the same request isn't another outflow")

        // A payout of Alice's the group approved and hasn't paid: open on bob's device.
        val payout = fixture.payable(all, alice, wallet, 30_000)
        val metadata = fixture.metadataOf(all, payout.member)
        val invoice = Bolt11Invoice.create(
            fixture.chain, 1_000_000.msat, randomBytes32(), PrivateKey(ByteVector32("11".repeat(32))),
            Either.Right(ByteVector32(Crypto.sha256(metadata.encodeToByteArray()))), CltvExpiryDelta(18),
            Features(Feature.VariableLengthOnion to FeatureSupport.Mandatory, Feature.PaymentSecret to FeatureSupport.Mandatory), randomBytes32(),
        ).write()
        val template = GroupWavelengthPaymentEvent.template(GroupWavelengthPaymentEvent.Payment(fixture.chain, 0, invoice, 50, 500, now + 3600, payout, metadata))
        fixture.fileSignedAsRoom(all, template.kind, template.tags, template.content)
        assertEquals("is from a member who has a payout waiting", refused(request(), on = bob))
    }

    // --- onchain (Phase 5, T6) ---

    private val coin = DepositKey.Onchain("c0".repeat(32), 0)

    /** A 40,000-sat deposit on Alice's own branch, on every device, confirmed unless said otherwise. */
    private suspend fun alicesCoin(key: DepositKey.Onchain = coin, sat: Long = 40_000, confirmed: Boolean = true, by: String = DepositRow.BY_BRANCH) = all.forEach { d ->
        d.db.depositDao().upsert(
            DepositRow(
                chatRoomId = fixture.roomId, chain = GroupWalletStatementEvent.chainName(fixture.chain),
                depositKey = key.text, source = DepositRow.ONCHAIN, amountMsat = sat * 1000,
                member = alice.publicKey.lowercase(), attributedBy = by,
                confirmedAt = if (confirmed) kotlin.time.Instant.fromEpochSeconds(now) else null,
            )
        )
    }

    /** Bob says where to be paid onchain too, on every device, newer than his deposits' destination. */
    private suspend fun bobPaidOnchain() {
        val destination = GroupPayoutDestinationEvent.build(
            keyOf(bob), fixture.roomId, fixture.chain, LightningAddress("member${bob.index}", "example.com"),
            onchainAddress = "bcrt1qzyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3lgth6c", createdAt = now + 1,
        )
        val tags = arrayOf(arrayOf("h", fixture.roomId))
        val id = com.vitorpamplona.quartz.nip01Core.crypto.EventHasher.hashId(bob.publicKey, destination.createdAt, GroupPayoutDestinationEvent.KIND, tags, destination.toJson())
        all.forEach { d ->
            d.db.marmotInnerEventDao().upsert(
                MarmotInnerEvent(id = id, publicKey = bob.publicKey, kind = GroupPayoutDestinationEvent.KIND, createdAt = kotlin.time.Instant.fromEpochSeconds(destination.createdAt), tags = tags, content = destination.toJson(), chatRoomId = fixture.roomId)
            )
        }
    }

    private fun onchainProposal(vararg moved: Pair<DepositKey.Onchain, Long>, from: GroupLightningFixture.Device = alice, to: String = bob.publicKey, askedAt: Long = now): Event {
        val asked = request(from = from, to = to, rail = Rail.ONCHAIN, sat = moved.sumOf { it.second }, askedAt = askedAt)
        val template = MemberTransferEvent.template(asked, moved.map { MemberTransferEvent.CoinShare(it.first, it.second) }, createdAt = askedAt)
        return Event("00".repeat(32), fixture.roomId, template.createdAt, template.kind, template.tags, template.content, "")
    }

    private suspend fun refusedOnchain(proposal: Event, on: GroupLightningFixture.Device = carol) =
        MemberTransfers.refuse(on.db, fixture.roomId, proposal, signsOnchain = true)

    @Test
    fun `no device signs an onchain transfer in the release that starts counting them`() = runBlocking<Unit> {
        opened()
        alicesCoin()
        bobPaidOnchain()
        assertEquals("moves coins, which this device doesn't sign yet", MemberTransfers.refuse(carol.db, fixture.roomId, onchainProposal(coin to 15_000)))
    }

    @Test
    fun `part of a confirmed coin the giver owns can be transferred, and then it is shared and neither's alone`() = runBlocking<Unit> {
        opened()
        alicesCoin()
        bobPaidOnchain()
        assertEquals(mapOf(coin to 40_000L), MemberTransfers.ownedCoins(carol.db, fixture.roomId, fixture.chain, alice.publicKey))
        val proposal = onchainProposal(coin to 15_000)
        assertNull(refusedOnchain(proposal))

        fixture.fileSignedAsRoom(all, proposal.kind, proposal.tags, createdAt = proposal.createdAt)
        val records = MemberBalances.recordsOf(carol.db, fixture.roomId, fixture.chain)
        val branch = assertNotNull(Depositors.statementOf(carol.db, fixture.roomId, fixture.chain, alice.publicKey)).branch
        assertEquals(
            com.machankura.dedun.compose.wallet.CoinOwnership.Owner.Shares(mapOf(alice.publicKey.lowercase() to 25_000L, bob.publicKey.lowercase() to 15_000L)),
            com.machankura.dedun.compose.wallet.CoinOwnership.ownerOf(fr.acinq.bitcoin.OutPoint(fr.acinq.bitcoin.TxId(coin.txid), coin.vout), branch, 40_000, records),
        )
        // A shared coin is nobody's alone to split again.
        assertEquals(emptyMap(), MemberTransfers.ownedCoins(carol.db, fixture.roomId, fixture.chain, alice.publicKey))
        assertEquals("names a coin the member shares with others", refusedOnchain(onchainProposal(coin to 10_000, askedAt = now + 5)))
    }

    @Test
    fun `an onchain transfer of a coin unseen, unconfirmed, unplaceable, too large or leaving dust, or to somebody unpayable onchain, is refused`() = runBlocking<Unit> {
        opened()
        alicesCoin()
        assertEquals("is to a member who has given no onchain address", refusedOnchain(onchainProposal(coin to 1_000)))
        bobPaidOnchain()
        val pending = DepositKey.Onchain("c1".repeat(32), 0)
        alicesCoin(pending, confirmed = false)
        val corrected = DepositKey.Onchain("c2".repeat(32), 0)
        alicesCoin(corrected, by = DepositRow.BY_GROUP)
        assertEquals("names a coin this device has not seen", refusedOnchain(onchainProposal(DepositKey.Onchain("c9".repeat(32), 0) to 1_000)))
        assertEquals("names a coin that hasn't confirmed", refusedOnchain(onchainProposal(pending to 1_000)))
        assertEquals("names a coin this device has not seen", refusedOnchain(onchainProposal(corrected to 1_000)), "its branch can't be placed without a wallet")
        assertEquals("moves more of a coin than the member holds of it", refusedOnchain(onchainProposal(coin to 40_001)))
        assertEquals("would leave the member a share of a coin too small to pay out", refusedOnchain(onchainProposal(coin to 39_500)))
    }

    @Test
    fun `a correction of a coin's owner voids a transfer of it by somebody who turned out not to own it`() = runBlocking<Unit> {
        opened()
        alicesCoin()
        bobPaidOnchain()
        val proposal = onchainProposal(coin to 15_000)
        fixture.fileSignedAsRoom(all, proposal.kind, proposal.tags, createdAt = proposal.createdAt)

        fixture.openDeposits(all, carol)
        // The deposit was Carol's all along, and the group says so after the transfer. Nothing stops the correction (the review's
        // second finding): it wins, and Alice's transfer of what wasn't hers moves nothing.
        val attribution = DepositAttributionEvent.template(fixture.chain, listOf(DepositAttributionEvent.Attribution(coin, carol.publicKey.lowercase(), 40_000)))
        val proposed = Event("00".repeat(32), fixture.roomId, now, attribution.kind, attribution.tags, attribution.content, "")
        assertNull(DepositLedger.refuseAttribution(bob.db, fixture.roomId, proposed))
        fixture.fileSignedAsRoom(all, attribution.kind, attribution.tags, createdAt = now + 10)
        // Read by the owner rule on the coin's real branch, as a payout of it would be: all Carol's, none of it Bob's.
        val branch = assertNotNull(Depositors.statementOf(bob.db, fixture.roomId, fixture.chain, alice.publicKey)).branch
        assertEquals(
            com.machankura.dedun.compose.wallet.CoinOwnership.Owner.Member(carol.publicKey.lowercase()),
            com.machankura.dedun.compose.wallet.CoinOwnership.ownerOf(
                fr.acinq.bitcoin.OutPoint(fr.acinq.bitcoin.TxId(coin.txid), coin.vout), branch, 40_000, MemberBalances.recordsOf(bob.db, fixture.roomId, fixture.chain),
            ),
        )
    }

    /** The second review's first finding: a transfer dated before one already recorded would be counted ahead of it. */
    @Test
    fun `a transfer dated before one the group already recorded is refused, on every rail`() = runBlocking<Unit> {
        opened()
        val first = request(sat = 1_000, askedAt = now - 100)
        val template = MemberTransferEvent.template(first, createdAt = now - 50)
        fixture.fileSignedAsRoom(all, template.kind, template.tags, createdAt = now - 50)

        val backdated = request(sat = 1_000, askedAt = now - 200)
        val earlier = MemberTransferEvent.template(backdated, createdAt = now - 60)
        assertEquals(
            "is dated before a transfer the group has already recorded",
            MemberTransfers.refuse(carol.db, fixture.roomId, Event("00".repeat(32), fixture.roomId, earlier.createdAt, earlier.kind, earlier.tags, earlier.content, "")),
        )
        assertNull(refused(request(sat = 1_000)), "and one dated now is fine")
    }

    @Test
    fun `a proposal the group would refuse is dropped before anybody sees it`() = runBlocking<Unit> {
        opened()
        // Carol proposes Alice's over-balance transfer directly, past her own device's check.
        val template = MemberTransferEvent.template(request(sat = 30_001))
        val session = FrostSigningManager.proposeSigning(carol.db, carol.room, carol.publicKey, template.kind, template.tags, template.content)
        fixture.pump(all, FrostSigningEvents.ALL)
        assertNull(bob.db.frostSigningSessionDao().getSessionById(session.id), "Bob's device dropped it")
    }

    @Test
    fun `the giver's balance changing before approval declines the transfer`() = runBlocking<Unit> {
        opened()
        val first = request(sat = 20_000, askedAt = now - 10)
        val big = request(sat = 15_000, askedAt = now - 5, to = bob.publicKey)
        val s1 = MemberTransfers.putToGroup(carol.db, carol.room, carol.publicKey, first)
        fixture.signSession(all, carol, s1.id)
        // Now Alice has 10,000; a session over 15,000 opened by a device that never ran the check is declined at approval.
        val template = MemberTransferEvent.template(big)
        val s2 = FrostSigningManager.proposeSigning(carol.db, carol.room, carol.publicKey, template.kind, template.tags, template.content)
        fixture.signSession(all, carol, s2.id)
        assertEquals(1, MemberTransfers.signedIn(bob.db, fixture.roomId, fixture.chain).size)
        assertEquals(10_000, wavelengthOf(bob, alice))
        assertTrue(carol.db.frostSigningSessionDao().getSessionById(s2.id)?.stage != FrostSigningStage.COMPLETE)
    }
}
