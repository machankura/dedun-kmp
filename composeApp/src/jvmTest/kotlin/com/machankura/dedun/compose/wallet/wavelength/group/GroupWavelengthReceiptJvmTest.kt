package com.machankura.dedun.compose.wallet.wavelength.group

import com.machankura.dedun.compose.database.model.GroupArkArriving
import com.machankura.dedun.compose.database.model.GroupArkBoarded
import com.machankura.dedun.compose.database.model.GroupArkClaim
import com.machankura.dedun.compose.database.model.GroupArkFee
import com.machankura.dedun.compose.database.model.GroupArkLeave
import com.machankura.dedun.compose.database.model.GroupArkPayment
import com.machankura.dedun.compose.database.model.GroupArkRound
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthExitEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthOnchainPayoutEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthPaymentEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthSnapshot
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthEntry.Kind
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthEntry.Source
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthReceipt.Proof
import fr.acinq.bitcoin.Chain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One entry as a record somebody can keep, and what the group can show for it.
 *
 * The proof line is the part worth a test rather than a reading: it is the one line in the app that
 * says *we cannot prove this*, and getting it wrong on a Lightning payout would have the group hand
 * somebody a receipt for a payment nobody can demonstrate happened.
 */
class GroupWavelengthReceiptJvmTest {
    private val wallet = "regtest:room:0"
    private val alice = "a".repeat(64)

    private val words = GroupWavelengthReceipt.Words(
        headline = "50,000 sats onchain to Zanele",
        status = "Paid",
        at = "3 Nov 2026, 14:05",
        amount = "50,000 sats",
        source = "Your device signed this.",
        fee = "Fee 300 sats",
        whose = "Whose: Zanele",
        holds = { id -> "What the group can show for it: $id" },
        noProof = "The group's wallet holds no proof of payment.",
    )

    private fun entry(kind: Kind, detail: GroupWavelengthEntry.Detail, source: Source = Source.THIS_DEVICE) =
        GroupWavelengthEntry(
            id = "$kind:1",
            kind = kind,
            status = GroupWavelengthEntry.Status.DONE,
            source = source,
            amountSat = 50_000,
            feeSat = 300,
            at = 1_799_999_000,
            member = alice,
            direction = GroupWavelengthEntry.Direction.OUT,
            detail = detail,
        )

    private val payment = GroupWavelengthPaymentEvent.Payment(
        chain = Chain.Regtest, wallet = 0, invoice = "lnbcrt1", maxFeeSat = 100,
        maxRefundHeight = 2_000, expiresAt = 1_800_000_000,
    )
    private val payout = GroupWavelengthOnchainPayoutEvent.Payout(
        chain = Chain.Regtest, wallet = 0, member = alice, destinationEventId = "dest:1",
        address = "bcrt1pqq", amountSat = 50_000, maxFeeSat = 300,
        expiresAt = 1_800_000_000, askedAt = 1_799_999_000,
    )

    /** One entry of each of the seven kinds, with the rows this device would hold for it. */
    private fun ofEachKind(): Map<Kind, GroupWavelengthEntry> = mapOf(
        Kind.DEPOSIT_ONCHAIN to entry(
            Kind.DEPOSIT_ONCHAIN,
            GroupWavelengthEntry.Detail.OnchainDeposit(boarded = GroupArkBoarded(wallet, "abc:0", "the-round", 50_000, "0014aa")),
        ),
        Kind.DEPOSIT_LIGHTNING to entry(
            Kind.DEPOSIT_LIGHTNING,
            GroupWavelengthEntry.Detail.LightningDeposit(claim = GroupArkClaim(wallet, "the-transfer", "07".repeat(32), 50_000)),
        ),
        Kind.PAYOUT_LIGHTNING to entry(
            Kind.PAYOUT_LIGHTNING,
            GroupWavelengthEntry.Detail.LightningPayout(approval = payment, signed = GroupArkPayment(wallet, "07".repeat(32), "the-tx")),
        ),
        Kind.PAYOUT_ONCHAIN to entry(
            Kind.PAYOUT_ONCHAIN,
            GroupWavelengthEntry.Detail.OnchainPayout(approval = payout, leave = GroupArkLeave(wallet, "out:1", "the-round", "0014bb", 50_000, 300, confirmed = true)),
        ),
        Kind.ROUND to entry(
            Kind.ROUND,
            GroupWavelengthEntry.Detail.Round(round = GroupArkRound(wallet, "the-commitment", "nh"), fee = GroupArkFee(wallet, "t", 300, 3, 1)),
        ),
        Kind.EXIT to entry(Kind.EXIT, GroupWavelengthEntry.Detail.Exit(GroupWavelengthExitEvent.Exit(Chain.Regtest, 0, 2_000, 1_000))),
        Kind.OPERATOR to entry(
            Kind.OPERATOR,
            GroupWavelengthEntry.Detail.OperatorLine(GroupWavelengthSnapshot.Line("l1", "send", "complete", 50_000), 3, 1, 100),
            source = Source.THE_OPERATOR,
        ),
    )

    @Test
    fun `every kind has a receipt, and every receipt says whose word it is`() {
        val each = ofEachKind()
        assertEquals(Kind.entries.toSet(), each.keys, "all seven kinds, including OPERATOR")
        each.forEach { (kind, entry) ->
            val receipt = GroupWavelengthReceipt.of(entry, words)
            assertTrue(receipt.startsWith(words.headline), "$kind leads with what it was")
            assertTrue(words.source in receipt.lines(), "$kind says whose word it is")
            assertTrue(words.amount in receipt.lines(), "$kind says how much")
            assertTrue(words.whose!! in receipt.lines(), "$kind says whose it is")
        }
    }

    /**
     * The one line in the app that says *we cannot prove this*. The preimage is the proof of a Lightning
     * payment and no device here ever sees one, so the receipt says so in the technical sheet's own
     * words rather than inventing a second sentence for it.
     */
    @Test
    fun `a Lightning payout says the group holds no proof, in the words the sheet already uses`() {
        val entry = ofEachKind().getValue(Kind.PAYOUT_LIGHTNING)
        assertEquals(Proof.NoneForLightning, GroupWavelengthReceipt.proof(entry))
        val receipt = GroupWavelengthReceipt.of(entry, words)
        assertTrue(receipt.endsWith(words.noProof!!), "it is the last line:\n$receipt")
    }

    /**
     * **Not a txid, which the plan expected.** An onchain payout's own row holds the round's commitment
     * and the destination script, and no transaction id: a payout leaves in a round, and the round is
     * what this device signed.
     */
    @Test
    fun `an onchain payout names the round it left in`() {
        val entry = ofEachKind().getValue(Kind.PAYOUT_ONCHAIN)
        assertEquals(Proof.Held("the-round"), GroupWavelengthReceipt.proof(entry))
        assertTrue(GroupWavelengthReceipt.of(entry, words).endsWith("What the group can show for it: the-round"))
    }

    @Test
    fun `each kind that holds something names the thing it holds`() {
        val each = ofEachKind()
        assertEquals(Proof.Held("abc:0"), GroupWavelengthReceipt.proof(each.getValue(Kind.DEPOSIT_ONCHAIN)), "the coin on the chain")
        assertEquals(Proof.Held("the-transfer"), GroupWavelengthReceipt.proof(each.getValue(Kind.DEPOSIT_LIGHTNING)), "the transfer, not the payment hash")
        assertEquals(Proof.Held("the-commitment"), GroupWavelengthReceipt.proof(each.getValue(Kind.ROUND)))
    }

    /** Nothing on any chain to point at, and the source line above has already said whose word it is. */
    @Test
    fun `an exit and a line of theirs alone name nothing, and say nothing extra`() {
        val each = ofEachKind()
        assertEquals(Proof.Unsaid, GroupWavelengthReceipt.proof(each.getValue(Kind.EXIT)))
        assertEquals(Proof.Unsaid, GroupWavelengthReceipt.proof(each.getValue(Kind.OPERATOR)))
        val receipt = GroupWavelengthReceipt.of(each.getValue(Kind.OPERATOR), words)
        assertEquals(words.source, receipt.lines().last(), "the source line is the last word on it")
        assertTrue("What the group can show" !in receipt)
    }

    /** A row this device holds nothing for names nothing, rather than naming an empty id. */
    @Test
    fun `a payout nobody has signed a round for yet names nothing`() {
        val waiting = entry(Kind.PAYOUT_ONCHAIN, GroupWavelengthEntry.Detail.OnchainPayout(approval = payout, leave = null))
        assertEquals(Proof.Unsaid, GroupWavelengthReceipt.proof(waiting))
        assertTrue("What the group can show" !in GroupWavelengthReceipt.of(waiting, words))
    }

    /** A round costs and does not move: no fee line where there is no fee, and no empty line either. */
    @Test
    fun `the lines nobody holds are left out rather than left empty`() {
        val bare = GroupWavelengthReceipt.of(
            ofEachKind().getValue(Kind.ROUND),
            words.copy(fee = null, whose = null),
        )
        assertTrue(bare.lines().none { it.isBlank() }, "no empty lines:\n$bare")
        assertTrue("Fee" !in bare)
        assertTrue("Whose" !in bare)
    }

    /**
     * The state `dedun`'s unconfirmed-deposits work brought in, which this branch's proof line predated.
     * A boarding payment seen on the chain has no `boarded` row until a round takes it, so the branch as
     * written answered `Unsaid` -- *nothing on any chain to point at* -- for the one entry whose money is
     * demonstrably on a chain and whose outpoint this device read off the chain itself.
     */
    @Test
    fun `a payment still arriving names the outpoint this device saw`() {
        val seen = GroupArkArriving(wallet, "deadbeef:0", 50_000, "0014aa", alice, firstSeenAtEpochSeconds = 1_799_999_000)
        val entry = entry(
            Kind.DEPOSIT_ONCHAIN,
            GroupWavelengthEntry.Detail.OnchainDeposit(
                arriving = GroupWavelengthEntry.Detail.Arriving(row = seen, depth = 0, needed = 6),
            ),
        ).copy(status = GroupWavelengthEntry.Status.ARRIVING, direction = GroupWavelengthEntry.Direction.IN)
        assertEquals(Proof.Held("deadbeef:0"), GroupWavelengthReceipt.proof(entry))
        assertTrue(words.holds!!("deadbeef:0") in GroupWavelengthReceipt.of(entry, words).lines())
    }

    /** Once a round has it the receipt names what the group signed, not what one device watched. */
    @Test
    fun `a boarded coin outranks the sighting it grew from`() {
        val entry = entry(
            Kind.DEPOSIT_ONCHAIN,
            GroupWavelengthEntry.Detail.OnchainDeposit(
                boarded = GroupArkBoarded(wallet, "abc:0", "the-round", 50_000, "0014aa"),
                arriving = GroupWavelengthEntry.Detail.Arriving(
                    row = GroupArkArriving(wallet, "deadbeef:0", 50_000, "0014aa", alice, firstSeenAtEpochSeconds = 1),
                    depth = 6, needed = 6,
                ),
            ),
        )
        assertEquals(Proof.Held("abc:0"), GroupWavelengthReceipt.proof(entry), "the signed coin, not the sighting")
    }
}
