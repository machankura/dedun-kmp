package com.machankura.dedun.compose.wallet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A payout's fee, shared pro rata to the sat -- `docs/payouts-only.md`, D5. */
class PayoutFeesTest {
    private val alice = "a1".repeat(32)
    private val bob = "b0".repeat(32)
    private val carol = "c0".repeat(32)

    @Test
    fun `one member pays the whole fee`() {
        assertEquals(mapOf(alice to 600L), PayoutFees.split(mapOf(alice to 120_000), 600))
    }

    @Test
    fun `the fee is shared in proportion to what each owns`() {
        assertEquals(mapOf(alice to 450L, bob to 150L), PayoutFees.split(mapOf(alice to 90_000, bob to 30_000), 600))
    }

    @Test
    fun `the sats rounding leaves go to whoever owns the most, then by key`() {
        // 100 / 3 is 33 each, with 1 left: the three own the same, so the lowest key takes it.
        assertEquals(mapOf(alice to 34L, bob to 33L, carol to 33L), PayoutFees.split(mapOf(carol to 1_000, alice to 1_000, bob to 1_000), 100))
        // 101 over 2:1:1 is 50, 25, 25 with 1 left, which goes to the largest owner.
        assertEquals(mapOf(alice to 51L, bob to 25L, carol to 25L), PayoutFees.split(mapOf(alice to 2_000, bob to 1_000, carol to 1_000), 101))
    }

    @Test
    fun `the shares always add up to the fee`() {
        val owned = mapOf(alice to 7_919L, bob to 104_729L, carol to 1_299_709L)
        (0L..2_000L step 7).forEach { fee -> assertEquals(fee, PayoutFees.split(owned, fee).values.sum()) }
    }

    @Test
    fun `a fee times a coin past a Long's range is still divided exactly`() {
        // 2,100,000,000,000,000 sats times a 10,000,000 sat fee is past 2^63.
        assertEquals(5_000_000L, PayoutFees.mulDiv(10_000_000, 1_050_000_000_000_000, 2_100_000_000_000_000))
        assertEquals(3_333_333L, PayoutFees.mulDiv(10_000_000, 700_000_000_000_000, 2_100_000_000_000_000))
        assertEquals(0L, PayoutFees.mulDiv(0, 5, 7))
        assertEquals(Long.MAX_VALUE / 3, PayoutFees.mulDiv(Long.MAX_VALUE, 1, 3))
    }

    @Test
    fun `nobody, or a member who owns nothing, is refused`() {
        assertFailsWith<IllegalArgumentException> { PayoutFees.split(emptyMap(), 100) }
        assertFailsWith<IllegalArgumentException> { PayoutFees.split(mapOf(alice to 0), 100) }
    }
}
