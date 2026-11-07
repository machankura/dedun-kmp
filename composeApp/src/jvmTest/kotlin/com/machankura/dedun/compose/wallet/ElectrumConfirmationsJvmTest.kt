package com.machankura.dedun.compose.wallet

import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.lightning.blockchain.electrum.IElectrumClient
import fr.acinq.lightning.blockchain.electrum.TransactionHistoryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** How deep a transaction is, on a connection that only knows the height it began at. */
class ElectrumConfirmationsJvmTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val electrum = FakeElectrum()
    private val wallets = GroupOnchainWallets(electrum, scope)

    private val tx = Transaction(
        2, listOf(TxIn(OutPoint(TxId(ByteVector32("cc".repeat(32))), 0), ByteVector.empty, 0xfffffffdL)),
        listOf(TxOut(Satoshi(9_000), ByteVector("0014" + "00".repeat(20)))), 0,
    )

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun `the client's own count stops at the height its connection began at`() = runBlocking {
        electrum.connect(height = 1_000)
        electrum.sent(tx, height = 1_001)
        electrum.mine(height = 1_001)
        assertEquals(0, electrum.getConfirmations(tx), "in a block, and reported as the mempool")

        electrum.mine(height = 1_010)
        electrum.sent(tx, height = 1_003)
        assertEquals(-2, electrum.getConfirmations(tx))
    }

    @Test
    fun `the wallets count a block newer than the connection from the tip`() = runBlocking {
        electrum.connect(height = 1_000)
        electrum.sent(tx, height = 1_001)
        electrum.mine(height = 1_001)
        assertEquals(1, wallets.confirmationsOf(tx, Chain.Regtest))

        electrum.mine(height = 1_010)
        assertEquals(10, wallets.confirmationsOf(tx, Chain.Regtest))
    }

    @Test
    fun `the wallets say what the chain has not got`() = runBlocking {
        electrum.connect(height = 1_000)
        assertNull(wallets.confirmationsOf(tx, Chain.Regtest), "nobody has heard of it")

        electrum.sent(tx, height = 0)
        assertEquals(0, wallets.confirmationsOf(tx, Chain.Regtest), "in the mempool")
    }

    @Test
    fun `a transaction whose parents are unconfirmed is in the mempool`() = runBlocking {
        electrum.connect(height = 1_000)
        electrum.sent(tx, height = -1)
        assertEquals(0, electrum.confirmationsOf(tx))
    }

    @Test
    fun `a closed connection is answered without asking, since the real client would hold the question`() = runBlocking {
        val closed = object : IElectrumClient by FakeElectrum() {
            override suspend fun getScriptHashHistory(scriptHash: ByteVector32): List<TransactionHistoryItem> = error("would wait for a connection")
        }
        assertNull(closed.confirmationsOf(tx))
    }

    @Test
    fun `a tip read from a server that is behind still leaves a mined transaction one deep`() = runBlocking {
        electrum.connect(height = 1_000)
        electrum.sent(tx, height = 1_005)
        assertEquals(1, electrum.confirmationsOf(tx))
    }
}
