package com.machankura.dedun.compose.wallet

import com.vitorpamplona.quartz.nip01Core.core.HexKey

/**
 * How a payout's fee is shared among the members it pays: `docs/payouts-only.md`, D5.
 *
 * Pro rata to what each owns among the inputs, rounded down, with the sats the rounding leaves
 * going one each to the members who own the most, ties broken by public key. The builder and
 * every signer run this one function, so a payout's outputs are checked to the sat.
 */
object PayoutFees {

    /** Each member of [owned] -- sats each owns among a payout's inputs -- and their share of [fee]. */
    fun split(owned: Map<HexKey, Long>, fee: Long): Map<HexKey, Long> {
        require(owned.isNotEmpty()) { "nobody to share a fee" }
        require(owned.values.all { it > 0 }) { "a member who owns nothing" }
        require(fee >= 0) { "a fee is not negative" }
        val total = owned.values.sum()
        val floors = owned.mapValues { (_, sat) -> mulDiv(fee, sat, total) }
        var left = fee - floors.values.sum()
        val order = owned.entries.sortedWith(compareByDescending<Map.Entry<HexKey, Long>> { it.value }.thenBy { it.key.lowercase() }).map { it.key }
        val shares = floors.toMutableMap()
        var at = 0
        while (left > 0) {
            val member = order[at % order.size]
            shares[member] = shares.getValue(member) + 1
            left--
            at++
        }
        return shares
    }

    /**
     * `a × b / c`, rounded down, exactly, for non-negative [a] and [b] and a positive [c]: a fee
     * times a coin's worth of sats can pass a `Long`, so the product is carried in two halves.
     */
    internal fun mulDiv(a: Long, b: Long, c: Long): Long {
        require(a >= 0 && b >= 0 && c > 0)
        val aHi = a ushr 32
        val aLo = a and MASK
        val bHi = b ushr 32
        val bLo = b and MASK
        // The 128-bit product as (high, low), each read unsigned.
        val lolo = aLo * bLo
        val hilo = aHi * bLo
        val lohi = aLo * bHi
        val hihi = aHi * bHi
        val middle = (lolo ushr 32) + (hilo and MASK) + (lohi and MASK)
        val low = (lolo and MASK) or (middle shl 32)
        val high = hihi + (hilo ushr 32) + (lohi ushr 32) + (middle ushr 32)
        return quotientOf(high, low, c)
    }

    /** `(high, low) / c`, by long division a bit at a time: the remainder stays below c, which is below 2^63. */
    private fun quotientOf(high: Long, low: Long, c: Long): Long {
        var remainder = 0L
        var quotient = 0L
        for (bit in 127 downTo 0) {
            val next = if (bit >= 64) (high ushr (bit - 64)) and 1L else (low ushr bit) and 1L
            // remainder < c < 2^63, so remainder shl 1 fits in an unsigned 64 bits; compare unsigned.
            val shifted = (remainder shl 1) or next
            val subtract = shifted.toULong() >= c.toULong()
            remainder = if (subtract) shifted - c else shifted
            if (bit < 64) quotient = (quotient shl 1) or (if (subtract) 1L else 0L)
            else check(!subtract) { "the quotient does not fit in a Long" }
        }
        return quotient
    }

    private const val MASK = 0xFFFFFFFFL
}
