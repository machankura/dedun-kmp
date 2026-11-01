package com.machankura.dedun.compose.wallet

import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.byteVector
import fr.acinq.lightning.DefaultSwapInParams
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.SwapInParams
import fr.acinq.lightning.blockchain.electrum.WalletState
import fr.acinq.lightning.blockchain.electrum.WalletState.AddressMeta
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.lightning.utils.sat
import fr.acinq.phoenixd.conf.LSP
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Taking back swap-in coins past the refund delay (docs/personal-swap-in-wallet.md, Phase 6).
 */
class SwapInRefundJvmTest {
    private val chain = Chain.Testnet3
    private val params = SwapInParams(DefaultSwapInParams.MinConfirmations, DefaultSwapInParams.MaxConfirmations, DefaultSwapInParams.RefundDelay)
    private val keys = LocalKeyManager(randomBytes32(), chain, LSP.from(chain).swapInXpub).swapInOnChainWallet
    private val tip = 3_000_000
    private val destination = Bitcoin.computeBIP86Address(PrivateKey(ByteVector32(Hex.decode("f1".padStart(64, '0')))).publicKey(), chain.chainHash)
    private val destinationScript = Script.write(Bitcoin.addressToPublicKeyScript(chain.chainHash, destination).right!!).byteVector()

    private fun coinAt(index: Int, amount: Long, confirmations: Int): Pair<String, WalletState.AddressState> {
        val protocol = keys.getSwapInProtocol(index)
        val tx = Transaction(2, listOf(TxIn(OutPoint(TxId(randomBytes32()), 0), 0)), listOf(TxOut(Satoshi(amount), protocol.pubkeyScript)), 0)
        val utxo = WalletState.Utxo(tx.txid, 0, (tip - confirmations + 1).toLong(), tx, AddressMeta.Derived(index))
        return protocol.address(chain) to WalletState.AddressState(AddressMeta.Derived(index), alreadyUsed = true, utxos = listOf(utxo))
    }

    // ---- where it may go ----

    @Test
    fun `an address on this network is a destination, with or without its scheme`() {
        assertEquals(SwapInRefund.Destination.Valid(destination, destinationScript), SwapInRefund.destination(chain, "  bitcoin:$destination?amount=1 ", emptySet()))
    }

    @Test
    fun `anything else is not`() {
        assertEquals(SwapInRefund.Destination.Unreadable, SwapInRefund.destination(chain, "not an address", emptySet()))
        val mainnet = Bitcoin.computeBIP86Address(PrivateKey(ByteVector32(Hex.decode("f1".padStart(64, '0')))).publicKey(), Chain.Mainnet.chainHash)
        assertEquals(SwapInRefund.Destination.Unreadable, SwapInRefund.destination(chain, mainnet, emptySet()))
    }

    @Test
    fun `one of the wallet's own deposit addresses is refused`() {
        val own = keys.getSwapInProtocol(3).address(chain)
        assertEquals(SwapInRefund.Destination.OwnDeposit, SwapInRefund.destination(chain, own, setOf(own)))
    }

    // ---- what is sent ----

    @Test
    fun `a coin one block short of the refund delay cannot be refunded`() {
        val state = WalletState(mapOf(coinAt(0, 50_000, params.refundDelay - 1)))
        assertNull(SwapInRefund.preview(state, tip, params, keys, destinationScript, FeeratePerKw(2_500.sat)))
    }

    @Test
    fun `at the refund delay every refundable coin goes to the destination, and only those`() {
        val state = WalletState(
            mapOf(
                coinAt(0, 50_000, params.refundDelay),
                coinAt(1, 30_000, params.refundDelay + 500),
                coinAt(2, 99_000, params.maxConfirmations), // locked: not yet
                coinAt(3, 77_000, 10), // still swappable
            )
        )
        val preview = assertIs<SwapInRefund.Preview>(SwapInRefund.preview(state, tip, params, keys, destinationScript, FeeratePerKw(2_500.sat)))
        assertEquals(2, preview.coins)
        assertEquals(80_000.sat, preview.refunded)
        assertEquals(1, preview.tx.txOut.size)
        assertEquals(destinationScript, preview.tx.txOut.single().publicKeyScript)
        assertEquals(preview.received, preview.tx.txOut.single().amount)
        assertTrue(preview.fee > 0.sat)
        // Each input waits out the refund delay, as its script path demands.
        assertTrue(preview.tx.txIn.all { it.sequence == params.refundDelay.toLong() })
    }
}
