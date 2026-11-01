package com.machankura.dedun.compose.wallet.lightning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a member's device works out a purchase costs (`docs/group-lightning-liquidity.md`, Phase 7),
 * held to what eclair charged in Phase 0's regtest run: 200,000 sats at 2 sat/vbyte for 2,521, our
 * share of the mining fee 221, the LSP's inputs' 200 and the service fee 2,100.
 */
class LiquidityPriceJvmTest {
    private val regtestRates = WalletWire.FundingRates(listOf(WalletWire.FundingRate(10_000, 10_000_000, 400, 100, 100, 1_000)), listOf("from_channel_balance"))

    @Test
    fun `the price is what eclair charged`() {
        val price = LiquidityPrice.price(regtestRates, 200_000, 2)!!
        assertEquals(LiquidityPrice.Price(ourMiningSat = 221, lspMiningSat = 200, serviceSat = 2_100), price)
        assertEquals(2_521, price.totalSat)
        assertEquals(3_781, LiquidityPrice.defaultCap(price))
    }

    @Test
    fun `no rate, no price, and the amounts offered are those the LSP sells under the cap`() {
        assertNull(LiquidityPrice.price(regtestRates, 5_000, 2))
        assertEquals(LiquidityPrice.OPTIONS, LiquidityPrice.offered(regtestRates, 25_000, null))
        assertEquals(listOf(100_000L, 250_000L, 500_000L), LiquidityPrice.offered(regtestRates, 25_000, 1_000_000), "a 25,000-sat channel under the mainnet cap")
        assertEquals(emptyList(), LiquidityPrice.offered(regtestRates, 950_000, 1_000_000), "a channel at the cap buys nothing")
        val narrow = WalletWire.FundingRates(listOf(WalletWire.FundingRate(200_000, 600_000, 400, 100, 100, 1_000)), listOf("from_channel_balance"))
        assertEquals(listOf(250_000L, 500_000L), LiquidityPrice.offered(narrow, 25_000, null))
    }

    @Test
    fun `a fee of a quarter of the amount or more is expensive, as Phoenix warns`() {
        assertFalse(LiquidityPrice.expensive(LiquidityPrice.price(regtestRates, 200_000, 2)!!, 200_000))
        assertTrue(LiquidityPrice.expensive(LiquidityPrice.Price(0, 0, 25_000), 100_000))
    }

    private fun channel(state: String = "Normal", capacitySat: Long = 25_000) = WalletWire.ChannelView("c", state, capacitySat, 21_900_000, 3_100_000, "f", 0, 1)
    private fun snapshot(channels: List<WalletWire.ChannelView>, rates: WalletWire.FundingRates? = regtestRates, feerate: Long? = 2) =
        WalletWire.Snapshot("w", 1, "n", 0, channels, emptyList(), fundingRates = rates, feeratePerByte = feerate)

    @Test
    fun `when the group can be asked, and why not`() {
        assertEquals(LiquidityPrice.Availability.NoOperator, LiquidityPrice.availability(snapshot(listOf(channel())), hasOperator = false, capSat = null))
        assertEquals(LiquidityPrice.Availability.NoPrices, LiquidityPrice.availability(null, true, null))
        assertEquals(LiquidityPrice.Availability.NoChannel, LiquidityPrice.availability(snapshot(emptyList()), true, null))
        assertEquals(LiquidityPrice.Availability.NoChannel, LiquidityPrice.availability(snapshot(listOf(channel(state = "Closing"))), true, null))
        assertEquals(LiquidityPrice.Availability.NoPrices, LiquidityPrice.availability(snapshot(listOf(channel()), rates = null), true, null))
        assertEquals(LiquidityPrice.Availability.NoPrices, LiquidityPrice.availability(snapshot(listOf(channel()), feerate = null), true, null))
        assertEquals(LiquidityPrice.Availability.AtTheCap, LiquidityPrice.availability(snapshot(listOf(channel(capacitySat = 950_000))), true, 1_000_000))
        val none = WalletWire.FundingRates(listOf(WalletWire.FundingRate(20_000_000, 30_000_000, 400, 100, 100, 1_000)), listOf("from_channel_balance"))
        assertEquals(LiquidityPrice.Availability.NothingSold, LiquidityPrice.availability(snapshot(listOf(channel()), rates = none), true, null))
        val available = LiquidityPrice.availability(snapshot(listOf(channel())), true, 1_000_000)
        assertTrue(available is LiquidityPrice.Availability.Available && available.offered == listOf(100_000L, 250_000L, 500_000L))
    }
}
