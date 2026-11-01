package com.machankura.dedun.compose.wallet.ark

import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Satoshi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Ark wallet where the groups ask for money or an address -- `docs/bark-wallet.md`, Phase 10. */
class ArkGroupBridgeJvmTest {
    private val invoice = "lnbc50u1pdeposit"

    private fun wallet(network: ArkNetwork, spendable: Long = 100_000) = FakeArkWallet(network = network, balance = ArkBalance(Satoshi(spendable))).apply {
        requests[invoice] = ArkPaymentRequest(Satoshi(5_000), "Deposit from @me to Treasury", listOf(ArkPaymentOption(ArkPaymentMethod.Bolt11(invoice), requiresAmount = false)), preferred = null)
    }

    private fun open(wallet: ArkWallet) = MutableStateFlow<ArkWalletState>(ArkWalletState.Open(wallet, ArkRecovery.NotRun))

    @Test
    fun `offered only when the wallet is open on the group's network`() {
        assertTrue(ArkGroupBridge.offered(open(wallet(ArkNetwork.Mainnet)).value, Chain.Mainnet))
        assertFalse(ArkGroupBridge.offered(open(wallet(ArkNetwork.Signet)).value, Chain.Mainnet))
        assertFalse(ArkGroupBridge.offered(ArkWalletState.Unavailable(ArkUnavailability.TorOn), Chain.Mainnet))
        assertTrue(ArkNetwork.Regtest.matches(Chain.Regtest))
        assertFalse(ArkNetwork.Mainnet.matches(Chain.Testnet3))
    }

    @Test
    fun `a deposit invoice is priced, then paid without a second amount`() = runTest {
        val wallet = wallet(ArkNetwork.Mainnet)
        val bridge = ArkGroupBridge(open(wallet))
        val quote = assertNotNull(bridge.quote(invoice, Chain.Mainnet))
        assertEquals(Satoshi(5_000), quote.amount)
        assertEquals(Satoshi(10), quote.fee)
        assertTrue(quote.covered)

        bridge.pay(quote, Chain.Mainnet)
        assertEquals(null, wallet.sent.single().second)
    }

    @Test
    fun `an invoice the balance cannot cover is priced as uncovered, and one on another network not at all`() = runTest {
        assertFalse(assertNotNull(ArkGroupBridge(open(wallet(ArkNetwork.Mainnet, spendable = 5_000))).quote(invoice, Chain.Mainnet)).covered)
        assertNull(ArkGroupBridge(open(wallet(ArkNetwork.Signet))).quote(invoice, Chain.Mainnet))
        assertNull(ArkGroupBridge(open(wallet(ArkNetwork.Mainnet))).quote("not an invoice", Chain.Mainnet))
    }

    @Test
    fun `the payout address comes from the wallet's onchain side, on the same network only`() = runTest {
        assertEquals("tb1qfake", ArkGroupBridge(open(wallet(ArkNetwork.Regtest))).onchainAddress(Chain.Regtest))
        assertNull(ArkGroupBridge(open(wallet(ArkNetwork.Signet))).onchainAddress(Chain.Mainnet))
    }
}
