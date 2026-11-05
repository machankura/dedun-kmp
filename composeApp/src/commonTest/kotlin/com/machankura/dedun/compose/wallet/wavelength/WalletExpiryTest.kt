package com.machankura.dedun.compose.wallet.wavelength

import engineering.lightning.wavelength.model.WalletVTXO
import kotlin.test.Test
import kotlin.test.assertEquals

class WalletExpiryTest {
    private val window = 648L

    private fun coin(expiry: Int, status: String = "live") = WalletVTXO(batchExpiry = expiry, status = status)

    @Test
    fun `no live coin has no deadline`() {
        assertEquals(WalletExpiry.Nothing, WalletExpiry.of(emptyList(), 1_000, window))
        assertEquals(WalletExpiry.Nothing, WalletExpiry.of(listOf(coin(900, "spent")), 1_000, window))
    }

    @Test
    fun `the earliest live coin decides`() {
        assertEquals(WalletExpiry.Fine(5_000), WalletExpiry.of(listOf(coin(9_000), coin(6_000), coin(1_100, "spent")), 1_000, window))
    }

    @Test
    fun `each threshold has its own answer`() {
        assertEquals(WalletExpiry.Fine(649), WalletExpiry.of(listOf(coin(1_649)), 1_000, window))
        assertEquals(WalletExpiry.Renewing(648), WalletExpiry.of(listOf(coin(1_648)), 1_000, window))
        assertEquals(WalletExpiry.Urgent(288), WalletExpiry.of(listOf(coin(1_288)), 1_000, window))
        assertEquals(WalletExpiry.Expired, WalletExpiry.of(listOf(coin(1_000)), 1_000, window))
    }

    @Test
    fun `a window of nothing never says the app is renewing`() {
        assertEquals(WalletExpiry.Fine(1_000), WalletExpiry.of(listOf(coin(2_000)), 1_000, 0))
    }
}
