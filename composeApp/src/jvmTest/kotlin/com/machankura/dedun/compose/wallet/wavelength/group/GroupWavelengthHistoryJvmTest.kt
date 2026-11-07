package com.machankura.dedun.compose.wallet.wavelength.group

import com.machankura.dedun.compose.database.model.DepositRow
import com.machankura.dedun.compose.database.model.GroupArkBoarded
import com.machankura.dedun.compose.database.model.GroupArkClaim
import com.machankura.dedun.compose.database.model.GroupArkDepositAddress
import com.machankura.dedun.compose.database.model.GroupArkFee
import com.machankura.dedun.compose.database.model.GroupArkLeave
import com.machankura.dedun.compose.database.model.GroupArkPayment
import com.machankura.dedun.compose.database.model.GroupArkRound
import com.machankura.dedun.compose.database.model.GroupArkVtxo
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthExitEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthOnchainPayoutEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthPaymentEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthSnapshot
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthEntry.Kind
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthEntry.Source
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthEntry.Status
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Feature
import fr.acinq.lightning.FeatureSupport
import fr.acinq.lightning.Features
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.utils.msat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The history a member reads, built from this device's rows and the operator's claim:
 * `docs/group-wavelength-transaction-details.md`, Phase 1. Pure, so none of this needs a database.
 */
class GroupWavelengthHistoryJvmTest {
    private val wallet = "regtest:room:0"
    private val room = "room"
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val txid = "1".repeat(64)
    private val other = "2".repeat(64)
    private val now = 1_800_000_000L

    private fun at(seconds: Long) = Instant.fromEpochSeconds(seconds)

    private fun invoice(amountMsat: Long? = 12_000_000) = Bolt11Invoice.create(
        Chain.Regtest, amountMsat?.msat, randomBytes32(), PrivateKey(randomBytes32()),
        Either.Left("the group's lunch"), CltvExpiryDelta(144),
        Features(Feature.VariableLengthOnion to FeatureSupport.Mandatory, Feature.PaymentSecret to FeatureSupport.Mandatory),
    ).write()

    private fun payout(bolt11: String = invoice(), expiresAt: Long = now + 3_600, member: String = bob) =
        GroupWavelengthPaymentEvent.Payment(Chain.Regtest, 0, bolt11, 50, 500, expiresAt, GroupWavelengthPaymentEvent.Payout(member, "destination"))

    private fun onchainPayout(amountSat: Long = 3_000, expiresAt: Long = now + 3_600, askedAt: Long = now - 60) =
        GroupWavelengthOnchainPayoutEvent.Payout(Chain.Regtest, 0, bob, "destination", "tb1qexample", amountSat, 200, expiresAt, askedAt)

    private fun deposit(key: String, amountSat: Long, member: String? = alice, seen: Long = now - 600, confirmed: Long? = now - 500) =
        DepositRow(
            chatRoomId = room,
            chain = "regtest",
            depositKey = key,
            source = DepositRow.WAVELENGTH,
            amountMsat = amountSat * 1_000,
            member = member,
            attributedBy = member?.let { DepositRow.BY_BOARDING_ADDRESS },
            seenAt = at(seen),
            confirmedAt = confirmed?.let(::at),
        )

    private fun line(
        id: String,
        kind: String = "payment",
        status: String = GroupWavelengthHistory.LINE_COMPLETE,
        amountSat: Long = 0,
        feeSat: Long = 0,
        at: Long = now - 100,
        failure: String = "",
        paymentHash: String = "",
    ) = GroupWavelengthSnapshot.Line(id, kind, status, amountSat, feeSat, at, failure, paymentHash)

    private fun snapshot(vararg lines: GroupWavelengthSnapshot.Line, term: Long = 3, tip: Long = 912_000) =
        GroupWavelengthSnapshot(wallet = wallet, term = term, sentAt = now, tip = tip, confirmedSat = 0, activity = lines.toList())

    private fun of(rows: GroupWavelengthHistory.Rows, snapshot: GroupWavelengthSnapshot? = null) =
        GroupWavelengthHistory.of(rows, snapshot, now)

    @Test
    fun `nothing at all is an empty history, not a list of zeros`() {
        assertEquals(emptyList(), of(GroupWavelengthHistory.Rows()))
    }

    @Test
    fun `a boarding deposit this device saw land is its own record, with whose it is`() {
        val entries = of(GroupWavelengthHistory.Rows(deposits = listOf(deposit("$txid:0", 50_000))))
        val entry = assertNotNull(entries.singleOrNull())
        assertEquals("deposit:$txid:0", entry.id)
        assertEquals(Kind.DEPOSIT_ONCHAIN, entry.kind)
        assertEquals(Status.DONE, entry.status)
        assertEquals(Source.THIS_DEVICE, entry.source)
        assertEquals(50_000, entry.amountSat)
        assertEquals(alice, entry.member)
        assertEquals(GroupWavelengthEntry.Direction.IN, entry.direction)
        assertEquals(now - 500, entry.at)
    }

    @Test
    fun `a boarding coin keeps its id when the round happens and the boarded row is forgotten`() {
        val boarded = GroupArkBoarded(wallet, "$txid:0", "commitment", 50_000, "0014aa")
        val signing = of(GroupWavelengthHistory.Rows(boarded = listOf(boarded)))
        assertEquals(Status.IN_FLIGHT, signing.single().status)

        // roundHappened files a DepositRow and then forgets the boarded row: the same entry, one id.
        val landed = of(GroupWavelengthHistory.Rows(deposits = listOf(deposit("$txid:0", 50_000))))
        assertEquals(signing.single().id, landed.single().id)
        assertEquals(Status.DONE, landed.single().status)
    }

    @Test
    fun `a boarded coin and its deposit row never show twice`() {
        val rows = GroupWavelengthHistory.Rows(
            boarded = listOf(GroupArkBoarded(wallet, "$txid:0", "commitment", 50_000, "0014aa")),
            deposits = listOf(deposit("$txid:0", 50_000)),
        )
        assertEquals(1, of(rows).size)
    }

    @Test
    fun `a boarding coin in flight is whose address it paid`() {
        val rows = GroupWavelengthHistory.Rows(
            boarded = listOf(GroupArkBoarded(wallet, "$txid:0", "commitment", 7_000, "0014AA")),
            addresses = listOf(GroupArkDepositAddress(wallet, "r1", bob, at(now - 900), address = "tb1qx", script = "0014aa")),
        )
        assertEquals(bob, of(rows).single().member)
    }

    @Test
    fun `a Lightning deposit runs from the invoice nobody paid to the claim to the coin`() {
        val hash = "c".repeat(64)
        val request = GroupArkDepositAddress(wallet, "r1", alice, at(now - 900), invoiceAmountSat = 9_000, invoice = "lnbcrt90u1x", paymentHash = hash)

        val waiting = of(GroupWavelengthHistory.Rows(addresses = listOf(request))).single()
        assertEquals("deposit-request:r1", waiting.id)
        assertEquals(Status.WAITING, waiting.status)
        assertEquals(Kind.DEPOSIT_LIGHTNING, waiting.kind)
        assertEquals(9_000, waiting.amountSat)

        val claimed = of(
            GroupWavelengthHistory.Rows(addresses = listOf(request), claims = listOf(GroupArkClaim(wallet, "ark", hash, 8_900)))
        ).single()
        assertEquals("deposit:ln:$hash", claimed.id)
        assertEquals(Status.IN_FLIGHT, claimed.status)
        assertEquals(8_900, claimed.amountSat, "what the claim keeps, not what the invoice asked for")

        // claimSettled files the deposit and forgets the claim: the same id again.
        val settled = of(
            GroupWavelengthHistory.Rows(addresses = listOf(request), deposits = listOf(deposit("ln:$hash", 8_900)))
        ).single()
        assertEquals(claimed.id, settled.id)
        assertEquals(Status.DONE, settled.status)
        assertEquals(Kind.DEPOSIT_LIGHTNING, settled.kind)
    }

    @Test
    fun `a boarding address is no entry, because it is permanent and reusable`() {
        val rows = GroupWavelengthHistory.Rows(
            addresses = listOf(GroupArkDepositAddress(wallet, "r1", alice, at(now - 900), address = "tb1qx", script = "0014aa"))
        )
        assertEquals(emptyList(), of(rows))
    }

    @Test
    fun `a request the operator hasn't answered is no entry either`() {
        val rows = GroupWavelengthHistory.Rows(
            addresses = listOf(GroupArkDepositAddress(wallet, "r1", alice, at(now - 900), invoiceAmountSat = 9_000))
        )
        assertEquals(emptyList(), of(rows))
    }

    @Test
    fun `a Lightning payout is approved, then on its way, then paid by the operator's word`() {
        val approval = payout()
        val hash = assertNotNull(approval.paymentHash).toString()

        val approved = of(GroupWavelengthHistory.Rows(payouts = listOf(approval))).single()
        assertEquals("payout-ln:$hash", approved.id)
        assertEquals(Status.WAITING, approved.status)
        assertEquals(Source.THIS_DEVICE, approved.source)
        assertEquals(12_000, approved.amountSat)
        assertEquals(bob, approved.member)
        assertEquals(GroupWavelengthEntry.Direction.OUT, approved.direction)

        val signed = of(
            GroupWavelengthHistory.Rows(payouts = listOf(approval), payments = listOf(GroupArkPayment(wallet, hash, "ark", amountSat = 12_010)))
        ).single()
        assertEquals(Status.IN_FLIGHT, signed.status)
        assertEquals(12_010, signed.amountSat, "what the vHTLC held is what the member is charged")

        val paid = of(
            GroupWavelengthHistory.Rows(payouts = listOf(approval), payments = listOf(GroupArkPayment(wallet, hash, "ark", amountSat = 12_010))),
            snapshot(line("l1", status = GroupWavelengthHistory.LINE_COMPLETE, amountSat = 12_010, feeSat = 41, paymentHash = hash)),
        ).single()
        assertEquals(Status.DONE, paid.status)
        assertEquals(Source.BOTH, paid.source, "the approval is the group's, whether it arrived is the operator's")
        assertEquals(41, paid.feeSat)
    }

    @Test
    fun `a Lightning payout the operator says failed says so, and carries its reason`() {
        val approval = payout()
        val hash = assertNotNull(approval.paymentHash).toString()
        val entry = of(
            GroupWavelengthHistory.Rows(payouts = listOf(approval)),
            snapshot(line("l1", status = GroupWavelengthHistory.LINE_FAILED, failure = "no route", paymentHash = hash)),
        ).single()
        assertEquals(Status.FAILED, entry.status)
        val detail = assertNotNull(entry.detail as? GroupWavelengthEntry.Detail.LightningPayout)
        assertEquals("no route", detail.line?.failure)
    }

    @Test
    fun `a Lightning payout nobody paid before its approval ran out is spent, not still waiting`() {
        val entry = of(GroupWavelengthHistory.Rows(payouts = listOf(payout(expiresAt = now - 1)))).single()
        assertEquals(Status.EXPIRED, entry.status)
    }

    @Test
    fun `an operator reporting a different amount than this device signed is a mismatch, and the device's figure wins`() {
        val approval = payout()
        val hash = assertNotNull(approval.paymentHash).toString()
        val entry = of(
            GroupWavelengthHistory.Rows(payouts = listOf(approval), payments = listOf(GroupArkPayment(wallet, hash, "ark", amountSat = 12_010))),
            snapshot(line("l1", status = GroupWavelengthHistory.LINE_COMPLETE, amountSat = 99_000, paymentHash = hash)),
        ).single()
        assertEquals(Source.MISMATCH, entry.source)
        assertEquals(12_010, entry.amountSat)
    }

    @Test
    fun `an onchain payout is this device's own record from approval to confirmation`() {
        val approval = onchainPayout()
        val waiting = of(GroupWavelengthHistory.Rows(onchainPayouts = listOf(approval))).single()
        assertEquals("payout-onchain:${approval.id}", waiting.id)
        assertEquals(Status.WAITING, waiting.status)
        assertEquals(3_000, waiting.amountSat)

        val leave = GroupArkLeave(wallet, approval.id, "commitment", "0014bb", 3_000, 180)
        val inRound = of(GroupWavelengthHistory.Rows(onchainPayouts = listOf(approval), leaves = listOf(leave))).single()
        assertEquals(Status.IN_FLIGHT, inRound.status)
        assertEquals(180, inRound.feeSat, "the payout's share of the round's fee, not the round's whole fee")

        val confirmed = of(
            GroupWavelengthHistory.Rows(onchainPayouts = listOf(approval), leaves = listOf(leave.copy(confirmed = true)))
        ).single()
        assertEquals(Status.DONE, confirmed.status)
        assertEquals(Source.THIS_DEVICE, confirmed.source, "nothing here is the operator's word")
    }

    @Test
    fun `an onchain payout no round paid before its approval ran out is expired`() {
        val entry = of(GroupWavelengthHistory.Rows(onchainPayouts = listOf(onchainPayout(expiresAt = now - 1)))).single()
        assertEquals(Status.EXPIRED, entry.status)
    }

    @Test
    fun `a round that only moved the group's own coins is a renewal, and says what it cost`() {
        val rows = GroupWavelengthHistory.Rows(
            coins = listOf(GroupArkVtxo(wallet, "$other:0", 49_000, origin = "commitment", settled = true)),
            fees = listOf(GroupArkFee(wallet, "commitment", 30, 3, now - 200)),
            rounds = listOf(GroupArkRound(wallet, "commitment", "note")),
        )
        val entry = of(rows).single()
        assertEquals("round:commitment", entry.id)
        assertEquals(Kind.ROUND, entry.kind)
        assertEquals(Status.DONE, entry.status)
        assertEquals(0, entry.amountSat)
        assertEquals(30, entry.feeSat)
        assertEquals(now - 200, entry.at)
        assertEquals(GroupWavelengthEntry.Direction.NONE, entry.direction)
    }

    @Test
    fun `a round a payout already speaks for gets no entry of its own, so no fee is counted twice`() {
        val approval = onchainPayout()
        val rows = GroupWavelengthHistory.Rows(
            leaves = listOf(GroupArkLeave(wallet, approval.id, "commitment", "0014bb", 3_000, 180)),
            fees = listOf(GroupArkFee(wallet, "commitment", 180, 3, now - 200)),
            rounds = listOf(GroupArkRound(wallet, "commitment", "note")),
            onchainPayouts = listOf(approval),
        )
        val entries = of(rows)
        assertEquals(listOf(Kind.PAYOUT_ONCHAIN), entries.map { it.kind })
        assertEquals(180, entries.single().feeSat)
    }

    @Test
    fun `a round a deposit already speaks for gets no entry of its own`() {
        val rows = GroupWavelengthHistory.Rows(
            boarded = listOf(GroupArkBoarded(wallet, "$txid:0", "commitment", 50_000, "0014aa")),
            fees = listOf(GroupArkFee(wallet, "commitment", 30, 3, now - 200)),
            rounds = listOf(GroupArkRound(wallet, "commitment", "note")),
        )
        assertEquals(listOf(Kind.DEPOSIT_ONCHAIN), of(rows).map { it.kind })
    }

    @Test
    fun `the round a deposit came in on is still a cost, once the boarded row has been forgotten`() {
        // roundHappened forgets the boarded coin, so nothing speaks for the round any more. The group did
        // pay for it, and a fee nobody can see is the thing T10 exists to stop.
        val rows = GroupWavelengthHistory.Rows(
            coins = listOf(GroupArkVtxo(wallet, "$other:0", 50_000, origin = "commitment", settled = true)),
            fees = listOf(GroupArkFee(wallet, "commitment", 30, 3, now - 200)),
            rounds = listOf(GroupArkRound(wallet, "commitment", "note")),
            deposits = listOf(deposit("$txid:0", 50_000)),
        )
        val entries = of(rows)
        assertEquals(setOf(Kind.DEPOSIT_ONCHAIN, Kind.ROUND), entries.map { it.kind }.toSet())
        assertEquals(0, entries.first { it.kind == Kind.DEPOSIT_ONCHAIN }.feeSat, "the fee is on the round, once")
        assertEquals(30, entries.first { it.kind == Kind.ROUND }.feeSat)
    }

    @Test
    fun `a transfer costs a fee and files no round, and is still a cost`() {
        val rows = GroupWavelengthHistory.Rows(fees = listOf(GroupArkFee(wallet, "ark", 12, 3, now - 200)))
        val entry = of(rows).single()
        assertEquals("round:ark", entry.id)
        assertEquals(Kind.ROUND, entry.kind)
        assertEquals(12, entry.feeSat)
        assertNull(assertNotNull(entry.detail as? GroupWavelengthEntry.Detail.Round).round)
    }

    @Test
    fun `the transfer a Lightning payout went into is not a cost of its own`() {
        val approval = payout()
        val hash = assertNotNull(approval.paymentHash).toString()
        val rows = GroupWavelengthHistory.Rows(
            fees = listOf(GroupArkFee(wallet, "ark", 12, 3, now - 200)),
            payments = listOf(GroupArkPayment(wallet, hash, "ark", amountSat = 12_010)),
            payouts = listOf(approval),
        )
        assertEquals(listOf(Kind.PAYOUT_LIGHTNING), of(rows).map { it.kind })
    }

    @Test
    fun `two approvals whose invoices don't decode keep ids of their own`() {
        val first = payout(bolt11 = "lnbcrt-not-an-invoice")
        val second = payout(bolt11 = "lnbcrt-also-not-one")
        assertNull(first.paymentHash, "the fixture has to be unreadable for this to mean anything")
        val entries = of(GroupWavelengthHistory.Rows(payouts = listOf(first, second)))
        assertEquals(2, entries.size)
        assertEquals(2, entries.map { it.id }.toSet().size)
    }

    @Test
    fun `asking the wallet to leave stands until it runs out, and is done once the coins have gone`() {
        val exit = GroupWavelengthExitEvent.Exit(Chain.Regtest, 0, expiresAt = now + 3_600, askedAt = now - 60)
        val coin = GroupArkVtxo(wallet, "$other:0", 49_000, settled = true)

        val asked = of(GroupWavelengthHistory.Rows(coins = listOf(coin), exits = listOf(exit)))
            .first { it.kind == Kind.EXIT }
        assertEquals("exit:${now - 60}", asked.id)
        assertEquals(Status.WAITING, asked.status)
        assertEquals(49_000, asked.amountSat)

        val gone = of(GroupWavelengthHistory.Rows(coins = listOf(coin.copy(spent = true)), exits = listOf(exit)))
            .first { it.kind == Kind.EXIT }
        assertEquals(Status.DONE, gone.status)

        val ranOut = of(GroupWavelengthHistory.Rows(coins = listOf(coin), exits = listOf(exit.copy(expiresAt = now - 1))))
            .first { it.kind == Kind.EXIT }
        assertEquals(Status.EXPIRED, ranOut.status)
    }

    @Test
    fun `a line this device can join to nothing is still shown, and marked as the operator's word alone`() {
        val entries = of(
            GroupWavelengthHistory.Rows(),
            snapshot(line("l1", kind = "payment", amountSat = 8_000, feeSat = 12, paymentHash = "d".repeat(64))),
        )
        val entry = assertNotNull(entries.singleOrNull())
        assertEquals("operator:l1", entry.id)
        assertEquals(Kind.OPERATOR, entry.kind)
        assertEquals(Source.THE_OPERATOR, entry.source)
        assertEquals(8_000, entry.amountSat)
        assertEquals(12, entry.feeSat)
        assertEquals(GroupWavelengthEntry.Direction.UNKNOWN, entry.direction)
        val detail = assertNotNull(entry.detail as? GroupWavelengthEntry.Detail.OperatorLine)
        assertEquals(3, detail.term)
        assertEquals(912_000, detail.tip)
        assertEquals(now, detail.sentAt)
    }

    @Test
    fun `an operator naming a line after an entry this device knows does not take it over`() {
        val approval = payout()
        val hash = assertNotNull(approval.paymentHash).toString()
        // The line's own id is the device-known entry's id, word for word. Its hash is somebody else's,
        // so nothing joins it and it stands as its own row rather than replacing the payout's.
        val entries = of(
            GroupWavelengthHistory.Rows(payouts = listOf(approval)),
            snapshot(line("payout-ln:$hash", amountSat = 1, paymentHash = "e".repeat(64))),
        )
        assertEquals(2, entries.size)
        val mine = assertNotNull(entries.firstOrNull { it.kind == Kind.PAYOUT_LIGHTNING })
        val theirs = assertNotNull(entries.firstOrNull { it.kind == Kind.OPERATOR })
        assertEquals("payout-ln:$hash", mine.id)
        assertEquals("operator:payout-ln:$hash", theirs.id)
        assertEquals(12_000, mine.amountSat, "the device's own figure, not the line's 1 sat")
    }

    @Test
    fun `a line already spoken for is not repeated as the operator's own`() {
        val approval = payout()
        val hash = assertNotNull(approval.paymentHash).toString()
        val entries = of(
            GroupWavelengthHistory.Rows(payouts = listOf(approval)),
            snapshot(line("l1", status = GroupWavelengthHistory.LINE_COMPLETE, amountSat = 12_000, paymentHash = hash)),
        )
        assertEquals(listOf(Kind.PAYOUT_LIGHTNING), entries.map { it.kind })
    }

    @Test
    fun `two lines the operator gave one id are one row`() {
        val entries = of(GroupWavelengthHistory.Rows(), snapshot(line("l1", amountSat = 1), line("l1", amountSat = 2)))
        assertEquals(1, entries.size)
    }

    @Test
    fun `newest first, and a row nobody holds a time for sorts last in a settled order`() {
        val older = deposit("$txid:0", 1_000, seen = now - 9_000, confirmed = now - 9_000)
        val newer = deposit("$other:0", 2_000, seen = now - 100, confirmed = now - 100)
        val timeless = GroupArkBoarded(wallet, "${"3".repeat(64)}:0", "commitment", 3_000, "0014cc")
        val alsoTimeless = GroupArkBoarded(wallet, "${"4".repeat(64)}:0", "commitment", 4_000, "0014dd")

        // The two timeless rows come last, and in id order however they arrived, so two reads agree.
        val entries = of(GroupWavelengthHistory.Rows(boarded = listOf(alsoTimeless, timeless), deposits = listOf(older, newer)))
        assertEquals(listOf(2_000L, 1_000L, 3_000L, 4_000L), entries.map { it.amountSat })
        assertNull(entries[2].at)
        assertNull(entries[3].at)
    }

    @Test
    fun `the operator's fifty-line cap drops no entry this device knows of`() {
        val mine = (0 until 60).map { deposit("${it.toString().padStart(64, '0')}:0", 1_000L + it, seen = now - it) }
        val entries = of(GroupWavelengthHistory.Rows(deposits = mine), snapshot())
        assertEquals(60, entries.size)
    }

    @Test
    fun `a restored device with no records of its own reads a history that is the operator's word throughout`() {
        val entries = of(
            GroupWavelengthHistory.Rows(),
            snapshot(
                line("l1", amountSat = 5_000, at = now - 10),
                line("l2", amountSat = 6_000, at = now - 20),
                line("l3", status = GroupWavelengthHistory.LINE_FAILED, amountSat = 7_000, at = now - 30),
            ),
        )
        assertEquals(3, entries.size)
        assertTrue(entries.all { it.source == Source.THE_OPERATOR })
        assertEquals(listOf(Status.DONE, Status.DONE, Status.FAILED), entries.map { it.status })
    }

    @Test
    fun `a half-restored device has some of each`() {
        val approval = payout()
        val hash = assertNotNull(approval.paymentHash).toString()
        val entries = of(
            GroupWavelengthHistory.Rows(payouts = listOf(approval), deposits = listOf(deposit("$txid:0", 50_000))),
            snapshot(
                line("l1", status = GroupWavelengthHistory.LINE_COMPLETE, amountSat = 12_000, paymentHash = hash),
                line("l2", amountSat = 500, at = now - 30),
            ),
        )
        assertEquals(setOf(Source.BOTH, Source.THIS_DEVICE, Source.THE_OPERATOR), entries.map { it.source }.toSet())
    }

    @Test
    fun `a deposit nobody has attributed is still a deposit`() {
        val entry = of(GroupWavelengthHistory.Rows(deposits = listOf(deposit("$txid:0", 4_000, member = null)))).single()
        assertNull(entry.member)
        assertEquals(Status.DONE, entry.status)
    }

    @Test
    fun `a deposit of another rail is not this wallet's`() {
        val onchain = deposit("$txid:0", 4_000).copy(source = DepositRow.ONCHAIN)
        val lightning = deposit("$other:0", 5_000).copy(source = DepositRow.LIGHTNING)
        assertEquals(emptyList(), of(GroupWavelengthHistory.Rows(deposits = listOf(onchain, lightning))))
    }

    @Test
    fun `an entry is settled once nothing more will happen to it`() {
        val waiting = of(GroupWavelengthHistory.Rows(onchainPayouts = listOf(onchainPayout()))).single()
        assertFalse(waiting.isSettled)
        val expired = of(GroupWavelengthHistory.Rows(onchainPayouts = listOf(onchainPayout(expiresAt = now - 1)))).single()
        assertTrue(expired.isSettled)
    }
}
