package com.machankura.dedun.compose.wallet.lightning

import com.machankura.dedun.compose.nostr.lightning.GroupLightningAuthorisationEvent

/**
 * What one member may still be paid out of the group's Lightning wallet: `docs/payouts-only.md`, D8.
 *
 * ```
 * available = their Lightning deposits (net of the LSP's fee)
 *           − payouts to them the node reports sent (amount + the fee it reports)
 *           − their authorised payouts not yet sent or expired (amount + maxfee)
 * ```
 *
 * A history, not a share of the channel: the reserve, the commitment fee and any liquidity bought
 * make the channel's spendable balance smaller than everybody's `available` added up, and who
 * bears that is the group's rule to make (`docs/member-deposits.md`, D8).
 */
object LightningBalance {

    data class Balance(
        val depositedMsat: Long,
        /** Paid out to them and settled, routing fees included. */
        val sentMsat: Long,
        /** Authorised for them and not yet settled or expired, at the most each may cost. */
        val openMsat: Long,
    ) {
        val availableMsat: Long get() = (depositedMsat - sentMsat - openMsat).coerceAtLeast(0)
    }

    /**
     * [member]'s balance from [depositedMsat] -- their Lightning deposits -- the [authorisations] the
     * group signed, and [sentFees]: the fee of each outgoing payment the node reports settled, by
     * payment hash. Only an authorisation naming [member] as its payout counts against them.
     */
    fun of(
        member: String,
        depositedMsat: Long,
        authorisations: List<GroupLightningAuthorisationEvent.Pay>,
        sentFees: Map<String, Long>,
        nowSeconds: Long,
    ): Balance {
        val theirs = authorisations.filter { it.payout?.member.equals(member, ignoreCase = true) }.distinctBy { it.paymentHash.lowercase() }
        val sent = theirs.mapNotNull { pay -> sentFees[pay.paymentHash.lowercase()]?.let { pay.amountMsat + it } }.sum()
        val open = theirs.filter { it.paymentHash.lowercase() !in sentFees && it.expiresAt > nowSeconds }.sumOf { it.budgetMsat }
        return Balance(depositedMsat, sent, open)
    }

    /** The least a payout's fee limit is: what the send form defaults to, ten sats. */
    const val MIN_FEE_MSAT: Long = 10_000

    /**
     * The most a member with [availableMsat] can be paid in one payment, in whole sats, and the fee
     * limit beside it -- a percent of the amount, never under [MIN_FEE_MSAT] -- so that the two
     * together never pass what they have: the whole of their balance, as one authorisation. Null
     * when there is too little to pay anything after the fee.
     */
    fun payable(availableMsat: Long): Pair<Long, Long>? {
        fun feeOf(amount: Long) = maxOf(MIN_FEE_MSAT, (amount + 99) / 100)
        // A guess from the percent, amount + amount/100 = available, walked down a sat at a time
        // until its fee fits beside it: a step or two, or a few more where the floor fee rules.
        var amount = availableMsat / 101 * 100 / 1000 * 1000
        while (amount > 0 && amount + feeOf(amount) > availableMsat) amount -= 1000
        return if (amount > 0) amount to feeOf(amount) else null
    }
}
