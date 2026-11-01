package com.machankura.dedun.compose.wallet.lightning

import com.machankura.dedun.compose.nostr.lightning.GroupLightningAuthorisationEvent
import fr.acinq.bitcoin.Chain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a member may still be paid over Lightning -- `docs/payouts-only.md`, D8. */
class LightningBalanceTest {
    private val alice = "a1".repeat(32)
    private val bob = "b0".repeat(32)
    private val now = 1_700_000_000L

    private fun pay(hash: String, amountMsat: Long, maxFeeMsat: Long, to: String?, expiresAt: Long = now + 3_600) =
        GroupLightningAuthorisationEvent.Pay(
            chain = Chain.Regtest, wallet = 0, invoice = "lnbcrt…", amountMsat = amountMsat, maxFeeMsat = maxFeeMsat,
            paymentHash = hash, expiresAt = expiresAt,
            payout = to?.let { GroupLightningAuthorisationEvent.Payout(it, "d".repeat(64)) },
        )

    @Test
    fun `deposits less what was sent, at its real fee, less what is open, at its most`() {
        val balance = LightningBalance.of(
            member = alice,
            depositedMsat = 100_000_000,
            authorisations = listOf(
                pay("01", 20_000_000, 300_000, alice),
                pay("02", 10_000_000, 200_000, alice),
            ),
            sentFees = mapOf("01" to 12_000),
            nowSeconds = now,
        )
        assertEquals(20_012_000, balance.sentMsat)
        assertEquals(10_200_000, balance.openMsat)
        assertEquals(69_788_000, balance.availableMsat)
    }

    @Test
    fun `another member's payout, an unlabelled payment, and an expired authorisation don't count`() {
        val balance = LightningBalance.of(
            member = alice,
            depositedMsat = 50_000_000,
            authorisations = listOf(
                pay("01", 20_000_000, 300_000, bob),
                pay("02", 20_000_000, 300_000, null),
                pay("03", 20_000_000, 300_000, alice, expiresAt = now - 1),
            ),
            sentFees = emptyMap(),
            nowSeconds = now,
        )
        assertEquals(50_000_000, balance.availableMsat)
    }

    @Test
    fun `a whole balance is paid in whole sats with a fee limit beside it, and the two never pass it`() {
        // 30,000 sats: a percent is 297 sats on 29,702.
        assertEquals(29_702_000L to 297_020L, LightningBalance.payable(30_000_000))
        // Under a thousand sats the floor fee of ten sats rules.
        assertEquals(500_000L to 10_000L, LightningBalance.payable(510_000))
        listOf(15_000L, 999_999L, 1_010_000L, 1_234_567_891L, 2_100_000_000_000_000_000L).forEach { available ->
            val (amount, fee) = LightningBalance.payable(available)!!
            assertTrue(amount + fee <= available, "$available")
            assertEquals(0, amount % 1000)
        }
        assertNull(LightningBalance.payable(10_500))
        assertNull(LightningBalance.payable(0))
    }

    @Test
    fun `an authorisation signed twice is counted once, and the balance never goes below nothing`() {
        val twice = pay("01", 60_000_000, 300_000, alice)
        val balance = LightningBalance.of(alice, 50_000_000, listOf(twice, twice), emptyMap(), now)
        assertEquals(60_300_000, balance.openMsat)
        assertEquals(0, balance.availableMsat)
    }
}
