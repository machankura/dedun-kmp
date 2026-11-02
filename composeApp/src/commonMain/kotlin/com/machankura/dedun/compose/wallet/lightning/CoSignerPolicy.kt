package com.machankura.dedun.compose.wallet.lightning

import fr.acinq.lightning.transactions.CommitmentSpec
import fr.acinq.lightning.transactions.IncomingHtlc
import fr.acinq.lightning.transactions.OutgoingHtlc

/**
 * What a co-signer lets a counterparty's commitment do to the group's money (`docs/group-lightning-wallet.md`,
 * Phase 9, check 5). Pure: the ledger it reads and returns is the caller's to store.
 *
 * In the counterparty's commitment `toRemote` is ours, and an HTLC *they* receive is one *we* offered.
 * What the group can claim from a commitment is [value]: our balance plus what we have offered and not yet
 * lost, because an offered HTLC comes back if it fails. Everything else is the counterparty's.
 *
 * The rules, in order:
 *
 * 1. **The commitment number moves forward by one**, or repeats with the same transaction (a
 *    retransmission after a reconnect).
 * 2. **A new HTLC we offer is a payment the group authorised**: its hash has an authorisation with budget
 *    left for it. Refusing here is the only refusal that works -- once the counterparty has the preimage,
 *    a commitment we do not sign does not stop it claiming on chain.
 * 3. **The value never falls below the floor.** The floor starts at the value of the channel's first
 *    commitment less the fee allowance, rises by what the group receives, and falls by what an authorised
 *    payment spends. A fall in value is explained by an authorised HTLC we offered disappearing without
 *    coming back, which consumes its budget; any other fall eats the fee allowance, and when that is gone
 *    the co-signer stops signing.
 *
 * A splice moves the channel to a new funding output, and each output keeps a ledger of its own: until
 * the splice confirms, both carry one commitment per update, at the same number. The new output's first
 * commitment is [openSplice]'s, from the parent's ledger (`docs/group-lightning-liquidity.md`, Phase 5).
 */
object CoSignerPolicy {
    data class OfferedHtlc(val paymentHash: String, val amountMsat: Long)

    /** What a co-signer remembers about a channel between signatures. */
    data class Ledger(
        val commitIndex: Long,
        val commitTxId: String,
        val oursMsat: Long,
        /** HTLCs we offered, by id. */
        val offered: Map<Long, OfferedHtlc>,
        /** HTLCs offered to us, by id, with their amounts. */
        val incoming: Map<Long, Long>,
        val floorMsat: Long,
    ) {
        val valueMsat: Long get() = oursMsat + offered.values.sumOf { it.amountMsat }
    }

    sealed class Verdict {
        /** Sign. [consumed] is how much of each authorisation's budget this commitment used, by payment hash. */
        data class Sign(val ledger: Ledger, val consumed: Map<String, Long>) : Verdict()
        data class Refuse(val reason: String) : Verdict()
    }

    private fun offeredBy(spec: CommitmentSpec): Map<Long, OfferedHtlc> =
        spec.htlcs.filterIsInstance<IncomingHtlc>().associate { it.add.id to OfferedHtlc(it.add.paymentHash.toHex(), it.add.amountMsat.toLong()) }

    private fun incomingTo(spec: CommitmentSpec): Map<Long, Long> =
        spec.htlcs.filterIsInstance<OutgoingHtlc>().associate { it.add.id to it.add.amountMsat.toLong() }

    /** The ledger for a channel's first commitment: whatever it holds is where the floor starts. */
    fun open(commitIndex: Long, commitTxId: String, remoteSpec: CommitmentSpec, feeAllowanceMsat: Long): Ledger {
        val offered = offeredBy(remoteSpec)
        val ours = remoteSpec.toRemote.toLong()
        return Ledger(commitIndex, commitTxId, ours, offered, incomingTo(remoteSpec), floorMsat = ours + offered.values.sumOf { it.amountMsat } - feeAllowanceMsat)
    }

    /**
     * The ledger for the first commitment on a splice's new funding output, from [parent], the ledger of
     * the output it spends. lightning-kmp signs it at the parent's commitment number, with the same HTLCs;
     * all that may change is our balance, which a liquidity purchase lowers by its fees. That fall is
     * allowed up to [maxFallMsat] -- the authorisation's fee cap -- and it lowers the floor with the value,
     * once, so the fee is not later read as money that went missing.
     */
    fun openSplice(parent: Ledger, commitIndex: Long, commitTxId: String, remoteSpec: CommitmentSpec, maxFallMsat: Long): Verdict {
        if (commitIndex != parent.commitIndex) return Verdict.Refuse("the splice's first commitment is number $commitIndex, not ${parent.commitIndex} as the channel's")
        val offered = offeredBy(remoteSpec)
        val incoming = incomingTo(remoteSpec)
        if (offered != parent.offered || incoming != parent.incoming) return Verdict.Refuse("the splice's first commitment carries other HTLCs than the channel's")
        val ours = remoteSpec.toRemote.toLong()
        val fall = parent.oursMsat - ours
        if (fall < 0) return Verdict.Refuse("the splice's first commitment gives the group more than the channel did")
        if (fall > maxFallMsat) return Verdict.Refuse("the splice takes $fall msat from the group, over the $maxFallMsat the group agreed to pay")
        return Verdict.Sign(Ledger(commitIndex, commitTxId, ours, offered, incoming, parent.floorMsat - fall), emptyMap())
    }

    /**
     * The ledger for the first commitment on a swap-in splice's new funding output, from [parent]
     * (`docs/group-lightning-swap-in.md`, Phase 4): the same number and HTLCs, and the group's balance
     * higher -- by what the splice adds, which the shared input's check (W7) holds it to. The floor rises
     * with the value, so the money that came in is guarded like the rest.
     */
    fun openSwapIn(parent: Ledger, commitIndex: Long, commitTxId: String, remoteSpec: CommitmentSpec): Verdict {
        if (commitIndex != parent.commitIndex) return Verdict.Refuse("the swap-in's first commitment is number $commitIndex, not ${parent.commitIndex} as the channel's")
        val offered = offeredBy(remoteSpec)
        val incoming = incomingTo(remoteSpec)
        if (offered != parent.offered || incoming != parent.incoming) return Verdict.Refuse("the swap-in's first commitment carries other HTLCs than the channel's")
        val ours = remoteSpec.toRemote.toLong()
        val rise = ours - parent.oursMsat
        if (rise <= 0) return Verdict.Refuse("the swap-in's first commitment does not give the group more than the channel did")
        return Verdict.Sign(Ledger(commitIndex, commitTxId, ours, offered, incoming, parent.floorMsat + rise), emptyMap())
    }

    /**
     * @param budgets what each authorisation has left to spend, by payment hash, in millisatoshi.
     * @param closed the payment hashes whose authorisations have expired: an HTLC already in flight for
     *   one still settles against its budget, and no new one is offered.
     * @param inFlightCapMsat the most the HTLCs the group offers may add up to at once: the statement's cap.
     */
    fun checkRemoteCommitment(
        previous: Ledger,
        commitIndex: Long,
        commitTxId: String,
        remoteSpec: CommitmentSpec,
        budgets: Map<String, Long>,
        closed: Set<String> = emptySet(),
        inFlightCapMsat: Long = Long.MAX_VALUE,
    ): Verdict {
        if (commitIndex == previous.commitIndex) {
            return if (commitTxId == previous.commitTxId) Verdict.Sign(previous, emptyMap())
            else Verdict.Refuse("commitment $commitIndex was already signed as a different transaction")
        }
        if (commitIndex != previous.commitIndex + 1) return Verdict.Refuse("commitment $commitIndex does not follow ${previous.commitIndex}")

        val offered = offeredBy(remoteSpec)
        val incoming = incomingTo(remoteSpec)
        val ours = remoteSpec.toRemote.toLong()

        // Rule 2: every newly offered HTLC is an authorised payment with budget left -- left after the
        // HTLCs for the same payment that are still in flight from earlier commitments.
        val remaining = budgets.toMutableMap()
        offered.filterKeys { it in previous.offered }.values.forEach { htlc ->
            remaining[htlc.paymentHash]?.let { remaining[htlc.paymentHash] = it - htlc.amountMsat }
        }
        val added = offered.filterKeys { it !in previous.offered }
        for ((id, htlc) in added.entries.sortedBy { it.key }) {
            val left = remaining[htlc.paymentHash] ?: return Verdict.Refuse("HTLC $id pays ${htlc.paymentHash.take(16)}, which the group has not authorised")
            if (htlc.paymentHash in closed) return Verdict.Refuse("HTLC $id pays ${htlc.paymentHash.take(16)}, whose authorisation has expired")
            if (htlc.amountMsat > left) return Verdict.Refuse("HTLC $id sends ${htlc.amountMsat} msat against an authorisation with $left msat left")
            remaining[htlc.paymentHash] = left - htlc.amountMsat
        }

        // The cap holds only for what the group sends: an HTLC offered to it is the counterparty's to
        // add, and refusing to sign over one would stall the channel rather than protect anything.
        val outgoing = offered.values.sumOf { it.amountMsat }
        if (added.isNotEmpty() && outgoing > inFlightCapMsat) return Verdict.Refuse("$outgoing msat would be in flight, over the cap of $inFlightCapMsat")

        // Rule 3: the value against the floor.
        val value = ours + offered.values.sumOf { it.amountMsat }
        val decrease = (previous.valueMsat - value).coerceAtLeast(0)
        val removedOffered = previous.offered.filterKeys { it !in offered }
        // A fall in value is first explained by authorised HTLCs that left without coming back.
        val consumed = mutableMapOf<String, Long>()
        var toExplain = decrease
        for ((_, htlc) in removedOffered.entries.sortedBy { it.key }) {
            if (toExplain <= 0) break
            val left = budgets[htlc.paymentHash] ?: continue
            val take = minOf(toExplain, htlc.amountMsat, left)
            if (take > 0) {
                consumed[htlc.paymentHash] = (consumed[htlc.paymentHash] ?: 0) + take
                toExplain -= take
            }
        }
        val spent = consumed.values.sum()
        // A rise in our balance as an HTLC offered to us disappears is the group being paid.
        val removedIncoming = previous.incoming.filterKeys { it !in incoming }.values.sum()
        val returned = removedOffered.values.sumOf { it.amountMsat } - spent
        val received = minOf(removedIncoming, (ours - previous.oursMsat - returned).coerceAtLeast(0))
        val floor = previous.floorMsat - spent + received
        if (value < floor) return Verdict.Refuse("the group could claim $value msat, under the floor of $floor msat")

        return Verdict.Sign(Ledger(commitIndex, commitTxId, ours, offered, incoming, floor), consumed)
    }
}
