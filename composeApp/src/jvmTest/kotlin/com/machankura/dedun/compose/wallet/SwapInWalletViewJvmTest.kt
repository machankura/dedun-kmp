package com.machankura.dedun.compose.wallet

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.lightning.DefaultSwapInParams
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.SwapInParams
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.blockchain.electrum.WalletState.AddressMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The swap-in screen's two lists, over hand-built wallet states (docs/personal-swap-in-wallet.md, Phase 1).
 */
class SwapInWalletViewJvmTest {
    private val params = SwapInParams(DefaultSwapInParams.MinConfirmations, DefaultSwapInParams.MaxConfirmations, DefaultSwapInParams.RefundDelay)
    private val tip = 900_000
    private val legacy = "legacy"

    private fun derived(index: Int) = "swap-in/$index"

    private fun utxo(amount: Long, meta: AddressMeta, blockHeight: Long): WalletState.Utxo {
        val tx = Transaction(2, listOf(TxIn(OutPoint(TxId(randomBytes32()), 0), 0)), listOf(TxOut(Satoshi(amount), ByteVector.empty)), 0)
        return WalletState.Utxo(tx.txid, 0, blockHeight, tx, meta)
    }

    private fun state(vararg entries: Pair<String, WalletState.AddressState>) = WalletState(entries.toMap())
    private fun unused(index: Int) = derived(index) to WalletState.AddressState(AddressMeta.Derived(index), alreadyUsed = false, utxos = emptyList())
    private fun used(index: Int, vararg utxos: WalletState.Utxo) = derived(index) to WalletState.AddressState(AddressMeta.Derived(index), alreadyUsed = true, utxos = utxos.toList())
    private fun legacyAddress(used: Boolean, vararg utxos: WalletState.Utxo) = legacy to WalletState.AddressState(AddressMeta.Single, alreadyUsed = used, utxos = utxos.toList())

    /** A coin at index 0 with [confirmations] at [tip]: 0 means the mempool. */
    private fun coinWith(confirmations: Int) = utxo(10_000, AddressMeta.Derived(0), if (confirmations == 0) 0L else (tip - confirmations + 1).toLong())

    private fun statusOf(coin: WalletState.Utxo, reserved: Set<OutPoint> = emptySet()) =
        SwapInWalletView.coins(state(used(0, coin), unused(1)), tip, params, reserved).single().status

    // ---- addresses ----

    @Test
    fun `no list before Electrum has answered`() {
        assertNull(SwapInWalletView.addresses(WalletState.empty, legacy))
    }

    @Test
    fun `a fresh wallet lists only the current address`() {
        val listed = SwapInWalletView.addresses(state(legacyAddress(used = false), unused(0), unused(1), unused(2)), legacy)
        assertEquals(listOf(SwapInAddress(derived(0), 0, SwapInAddress.Status.CURRENT, Satoshi(0))), listed)
    }

    @Test
    fun `a paid address stays listed and the current one moves on`() {
        val coin = utxo(25_000, AddressMeta.Derived(0), 0)
        val listed = SwapInWalletView.addresses(state(legacyAddress(used = false), used(0, coin), unused(1), unused(2), unused(3)), legacy)!!
        assertEquals(listOf(1 to SwapInAddress.Status.CURRENT, 0 to SwapInAddress.Status.USED), listed.map { it.index to it.status })
        assertEquals(Satoshi(25_000), listed.last().balance)
    }

    @Test
    fun `a used address whose coins have been swapped in is still used, and holds nothing`() {
        val listed = SwapInWalletView.addresses(state(used(0), unused(1)), legacy)!!
        assertEquals(SwapInAddress(derived(0), 0, SwapInAddress.Status.USED, Satoshi(0)), listed.last())
    }

    @Test
    fun `a payment above the current address is listed, newest first`() {
        val listed = SwapInWalletView.addresses(state(unused(0), unused(1), used(2), unused(3)), legacy)!!
        assertEquals(listOf(2 to SwapInAddress.Status.USED, 0 to SwapInAddress.Status.CURRENT), listed.map { it.index to it.status })
    }

    @Test
    fun `the legacy address is listed last, and only once it has been paid`() {
        val coin = utxo(5_000, AddressMeta.Single, tip.toLong())
        val listed = SwapInWalletView.addresses(state(legacyAddress(used = true, coin), unused(0)), legacy)!!
        assertEquals(SwapInAddress(legacy, null, SwapInAddress.Status.USED, Satoshi(5_000)), listed.last())
        assertEquals(1, SwapInWalletView.addresses(state(legacyAddress(used = false), unused(0)), legacy)!!.size)
    }

    // ---- coins, at each boundary ----

    @Test
    fun `a coin in the mempool is unconfirmed`() {
        assertEquals(SwapInCoin.Status.Unconfirmed, statusOf(coinWith(0)))
    }

    @Test
    fun `a coin short of three confirmations is confirming, and says how far along`() {
        assertEquals(SwapInCoin.Status.Confirming(1, 3), statusOf(coinWith(1)))
        assertEquals(SwapInCoin.Status.Confirming(2, 3), statusOf(coinWith(2)))
    }

    @Test
    fun `at three confirmations a coin is ready`() {
        assertEquals(SwapInCoin.Status.Ready, statusOf(coinWith(3)))
    }

    @Test
    fun `two weeks before the swap window closes a ready coin is expiring`() {
        val warnFrom = params.maxConfirmations - SwapInWalletView.EXPIRY_WARNING_BLOCKS
        assertEquals(SwapInCoin.Status.Ready, statusOf(coinWith(warnFrom)))
        assertEquals(SwapInCoin.Status.ExpiringSoon(SwapInWalletView.EXPIRY_WARNING_BLOCKS - 1), statusOf(coinWith(warnFrom + 1)))
        assertEquals(SwapInCoin.Status.ExpiringSoon(1), statusOf(coinWith(params.maxConfirmations - 1)))
    }

    @Test
    fun `from the end of the swap window to the refund delay a coin is locked`() {
        assertEquals(SwapInCoin.Status.Locked(params.refundDelay - params.maxConfirmations), statusOf(coinWith(params.maxConfirmations)))
        assertEquals(SwapInCoin.Status.Locked(1), statusOf(coinWith(params.refundDelay - 1)))
    }

    @Test
    fun `at the refund delay a coin is refundable`() {
        assertEquals(SwapInCoin.Status.Refundable, statusOf(coinWith(params.refundDelay)))
    }

    @Test
    fun `a coin a funding attempt holds is swapping, whatever its depth`() {
        val coin = coinWith(5)
        assertEquals(SwapInCoin.Status.Swapping, statusOf(coin, reserved = setOf(coin.outPoint)))
    }

    @Test
    fun `coins are listed mempool first, then newest first`() {
        val old = utxo(1, AddressMeta.Derived(0), (tip - 99).toLong())
        val mempool = utxo(2, AddressMeta.Derived(1), 0)
        val recent = utxo(3, AddressMeta.Single, tip.toLong())
        val coins = SwapInWalletView.coins(state(used(0, old), used(1, mempool), legacyAddress(used = true, recent), unused(2)), tip, params, emptySet())
        assertEquals(listOf(Satoshi(2), Satoshi(3), Satoshi(1)), coins.map { it.amount })
        assertEquals(listOf(1, null, 0), coins.map { it.index })
        assertEquals(listOf(0, 1, 100), coins.map { it.confirmations })
    }
}
