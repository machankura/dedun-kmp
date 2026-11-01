package com.machankura.dedun.compose.wallet.lightning

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.CltvExpiry
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.transactions.CommitmentSpec
import fr.acinq.lightning.transactions.DirectedHtlc
import fr.acinq.lightning.transactions.IncomingHtlc
import fr.acinq.lightning.transactions.OutgoingHtlc
import fr.acinq.lightning.utils.msat
import fr.acinq.lightning.wire.OnionRoutingPacket
import fr.acinq.lightning.wire.UpdateAddHtlc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What a co-signer lets the counterparty's commitment do to the group's money. Every spec here is the
 * counterparty's view: `toRemote` is ours, an HTLC they receive is one we offered.
 */
class CoSignerPolicyTest {
    private val channel = ByteVector32.Zeroes
    private val onion = OnionRoutingPacket(0, ByteVector(ByteArray(33) { if (it == 0) 2 else 0 }), ByteVector.empty, ByteVector32.Zeroes)
    private val allowance = 5_000_000L
    private fun hash(n: Int) = ByteVector32(ByteArray(32) { n.toByte() })

    private fun add(id: Long, amount: Long, hash: ByteVector32) = UpdateAddHtlc(channel, id, amount.msat, hash, CltvExpiry(800_000), onion)

    /** We offered these (they receive them). */
    private fun offered(id: Long, amount: Long, h: Int): DirectedHtlc = IncomingHtlc(add(id, amount, hash(h)))

    /** They offered these to us. */
    private fun toUs(id: Long, amount: Long, h: Int): DirectedHtlc = OutgoingHtlc(add(id, amount, hash(h)))

    private fun spec(ours: Long, theirs: Long, vararg htlcs: DirectedHtlc) = CommitmentSpec(htlcs.toSet(), FeeratePerKw(Satoshi(2_500)), toLocal = theirs.msat, toRemote = ours.msat)

    /** The commitment after [previous]'s, as the counterparty would send it. */
    private fun next(previous: CoSignerPolicy.Ledger, spec: CommitmentSpec, budgets: Map<String, Long> = emptyMap(), closed: Set<String> = emptySet()): CoSignerPolicy.Verdict =
        CoSignerPolicy.checkRemoteCommitment(previous, previous.commitIndex + 1, "tx${previous.commitIndex + 1}:$spec".hashCode().toString(), spec, budgets, closed)

    private fun signed(verdict: CoSignerPolicy.Verdict) = assertIs<CoSignerPolicy.Verdict.Sign>(verdict)
    private fun refused(verdict: CoSignerPolicy.Verdict) = assertIs<CoSignerPolicy.Verdict.Refuse>(verdict)

    private val start = CoSignerPolicy.open(0, "tx0", spec(ours = 100_000_000, theirs = 900_000_000), allowance)

    @Test
    fun `receiving raises the floor, so what was received cannot be drained`() {
        val incoming = signed(next(start, spec(100_000_000, 850_000_000, toUs(0, 50_000_000, 1)))).ledger
        val received = signed(next(incoming, spec(150_000_000, 850_000_000))).ledger
        assertEquals(start.floorMsat + 50_000_000, received.floorMsat)
        // A fall bigger than the fee allowance, with nothing authorised to explain it, is refused.
        refused(next(received, spec(140_000_000, 860_000_000)))
        // Within the allowance it is a fee.
        signed(next(received, spec(147_000_000, 853_000_000)))
    }

    @Test
    fun `an outgoing payment needs an authorisation, and spends its budget when it settles`() {
        val budgets = mapOf(hash(7).toHex() to 30_000_000L)
        refused(next(start, spec(70_000_000, 900_000_000, offered(0, 30_000_000, 7))))
        val inFlight = signed(next(start, spec(70_000_000, 900_000_000, offered(0, 30_000_000, 7)), budgets)).ledger
        assertEquals(start.valueMsat, inFlight.valueMsat, "an HTLC in flight is still the group's to claim back")
        val settled = signed(next(inFlight, spec(70_000_000, 930_000_000), budgets))
        assertEquals(mapOf(hash(7).toHex() to 30_000_000L), settled.consumed)
        assertEquals(start.floorMsat - 30_000_000, settled.ledger.floorMsat)
    }

    @Test
    fun `a failed payment comes back and spends nothing`() {
        val budgets = mapOf(hash(7).toHex() to 30_000_000L)
        val inFlight = signed(next(start, spec(70_000_000, 900_000_000, offered(0, 30_000_000, 7)), budgets)).ledger
        val failed = signed(next(inFlight, spec(100_000_000, 900_000_000), budgets))
        assertTrue(failed.consumed.isEmpty())
        assertEquals(start.floorMsat, failed.ledger.floorMsat)
        // So the payment can be tried again.
        signed(next(failed.ledger, spec(70_000_000, 900_000_000, offered(1, 30_000_000, 7)), budgets))
    }

    @Test
    fun `one authorisation cannot be offered twice over`() {
        val budgets = mapOf(hash(7).toHex() to 30_000_000L)
        val first = signed(next(start, spec(80_000_000, 900_000_000, offered(0, 20_000_000, 7)), budgets)).ledger
        // A second part of the same payment fits only in what is left of its budget.
        refused(next(first, spec(60_000_000, 900_000_000, offered(0, 20_000_000, 7), offered(1, 20_000_000, 7)), budgets))
        signed(next(first, spec(70_000_000, 900_000_000, offered(0, 20_000_000, 7), offered(1, 10_000_000, 7)), budgets))
    }

    @Test
    fun `a spent budget is gone, whatever arrives afterwards`() {
        val budgets = mapOf(hash(7).toHex() to 30_000_000L)
        val inFlight = signed(next(start, spec(70_000_000, 900_000_000, offered(0, 30_000_000, 7)), budgets)).ledger
        val spent = signed(next(inFlight, spec(70_000_000, 930_000_000), budgets)).ledger
        val paid = signed(next(signed(next(spent, spec(70_000_000, 900_000_000, toUs(1, 30_000_000, 2)))).ledger, spec(100_000_000, 900_000_000))).ledger
        // The receipt does not refill the budget: what came in is the group's, and the floor says so.
        refused(next(paid, spec(70_000_000, 930_000_000)))
    }

    @Test
    fun `the commitment number moves forward one at a time, and a repeat is the same transaction`() {
        assertIs<CoSignerPolicy.Verdict.Sign>(CoSignerPolicy.checkRemoteCommitment(start, 0, "tx0", spec(100_000_000, 900_000_000), emptyMap()))
        refused(CoSignerPolicy.checkRemoteCommitment(start, 0, "other", spec(100_000_000, 900_000_000), emptyMap()))
        refused(CoSignerPolicy.checkRemoteCommitment(start, 2, "tx2", spec(100_000_000, 900_000_000), emptyMap()))
    }

    @Test
    fun `an expired authorisation settles what is in flight, and offers nothing new`() {
        val budgets = mapOf(hash(7).toHex() to 30_000_000L)
        val closed = setOf(hash(7).toHex())
        refused(next(start, spec(80_000_000, 900_000_000, offered(0, 20_000_000, 7)), budgets, closed))
        val inFlight = signed(next(start, spec(80_000_000, 900_000_000, offered(0, 20_000_000, 7)), budgets)).ledger
        refused(next(inFlight, spec(70_000_000, 900_000_000, offered(0, 20_000_000, 7), offered(1, 10_000_000, 7)), budgets, closed))
        val settled = signed(next(inFlight, spec(80_000_000, 920_000_000), budgets, closed))
        assertEquals(mapOf(hash(7).toHex() to 20_000_000L), settled.consumed)
    }

    @Test
    fun `what the group offers at once stays under the cap, and what it is offered is not counted`() {
        val budgets = mapOf(hash(7).toHex() to 50_000_000L, hash(8).toHex() to 50_000_000L)
        fun capped(previous: CoSignerPolicy.Ledger, spec: CommitmentSpec) =
            CoSignerPolicy.checkRemoteCommitment(previous, previous.commitIndex + 1, "tx${previous.commitIndex + 1}:$spec".hashCode().toString(), spec, budgets, inFlightCapMsat = 40_000_000)
        val one = signed(capped(start, spec(70_000_000, 900_000_000, offered(0, 30_000_000, 7)))).ledger
        refused(capped(one, spec(50_000_000, 900_000_000, offered(0, 30_000_000, 7), offered(1, 20_000_000, 8))))
        signed(capped(one, spec(70_000_000, 850_000_000, offered(0, 30_000_000, 7), toUs(1, 50_000_000, 2))))
    }
}
