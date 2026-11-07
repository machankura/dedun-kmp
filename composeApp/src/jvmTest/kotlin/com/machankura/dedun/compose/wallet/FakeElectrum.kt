package com.machankura.dedun.compose.wallet

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.lightning.blockchain.electrum.ElectrumConnectionStatus
import fr.acinq.lightning.blockchain.electrum.ElectrumSubscriptionResponse
import fr.acinq.lightning.blockchain.electrum.GetMerkleResponse
import fr.acinq.lightning.blockchain.electrum.HeaderSubscriptionResponse
import fr.acinq.lightning.blockchain.electrum.IElectrumClient
import fr.acinq.lightning.blockchain.electrum.ScriptHashSubscriptionResponse
import fr.acinq.lightning.blockchain.electrum.ServerVersionResponse
import fr.acinq.lightning.blockchain.electrum.TransactionHistoryItem
import fr.acinq.lightning.blockchain.electrum.UnspentItem
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A chain that only exists in a test.
 *
 * `IElectrumClient` is small and documented never to throw, so a fake of it is
 * a few maps: what each script has been paid, what of it is unspent, and the
 * transactions that paid it.
 *
 * Closed until [connect], and a connection is as the real client keeps one: its status holds the
 * height it began at, whatever [mine] does after, and only asking gets the tip.
 */
internal class FakeElectrum : IElectrumClient {
    val unspents = mutableMapOf<ByteVector32, List<UnspentItem>>()
    val txs = mutableMapOf<TxId, Transaction>()
    val subscribed = mutableSetOf<ByteVector32>()
    override val notifications = MutableSharedFlow<ElectrumSubscriptionResponse>(extraBufferCapacity = 64)
    override val connectionStatus = MutableStateFlow<ElectrumConnectionStatus>(ElectrumConnectionStatus.Closed(null))

    /** The best block: what asking for the header answers, and never what [connectionStatus] says after the handshake. */
    var tip = 100
        private set

    /** How many transactions have touched each script: Electrum's status is over the history, not the unspents. */
    private val history = mutableMapOf<ByteVector32, Int>()

    /** The transactions that touched each script, in order: what `get_history` answers. */
    private val historyTxs = mutableMapOf<ByteVector32, List<TransactionHistoryItem>>()

    private fun status(scriptHash: ByteVector32): String? = history[scriptHash]?.let { "used:$it" }

    /** Opens a connection with the tip at [height], which is where its status stays. */
    fun connect(height: Int) {
        tip = height
        connectionStatus.value = ElectrumConnectionStatus.Connected(ServerVersionResponse("fake", "1.4"), height, Block.RegtestGenesisBlock.header)
    }

    /** The chain reaches [height]: a header notification, and nothing on the connection's status. */
    suspend fun mine(height: Int) {
        tip = height
        notifications.emit(HeaderSubscriptionResponse(height, Block.RegtestGenesisBlock.header))
    }

    /** Pays [script] in a fresh transaction, confirmed at [height] (0 for the mempool), and tells the wallet. */
    suspend fun pay(script: ByteVector, amount: Satoshi, height: Long = 100) {
        val tx = Transaction(
            version = 2,
            txIn = listOf(TxIn(OutPoint(TxId(ByteVector32(Crypto.sha256(script.toByteArray() + txs.size.toByte()))), 0), ByteVector.empty, 0xffffffffL)),
            txOut = listOf(TxOut(amount, script)),
            lockTime = 0,
        )
        txs[tx.txid] = tx
        val hash = scriptHash(script)
        unspents[hash] = unspents[hash].orEmpty() + UnspentItem(tx.txid, 0, amount.sat, height)
        history[hash] = (history[hash] ?: 0) + 1
        historyTxs[hash] = historyTxs[hash].orEmpty() + TransactionHistoryItem(height.toInt(), tx.txid)
        notifications.emit(ScriptHashSubscriptionResponse(hash, status(hash)))
    }

    /** Spends everything [script] holds: its unspents go, its history stays. */
    suspend fun spend(script: ByteVector) {
        val hash = scriptHash(script)
        unspents.remove(hash)
        history[hash] = (history[hash] ?: 0) + 1
        notifications.emit(ScriptHashSubscriptionResponse(hash, status(hash)))
    }

    /** The transaction paying [script] most recently, as [pay] made it. */
    fun fundingOf(script: ByteVector): Transaction = txs.getValue(historyTxs.getValue(scriptHash(script)).last().txid)

    /** Spends what [script] holds with [tx] itself, which joins its history as a real spend would, confirmed at [height]. */
    suspend fun spendWith(script: ByteVector, tx: Transaction, height: Int = 100) {
        val hash = scriptHash(script)
        txs[tx.txid] = tx
        historyTxs[hash] = historyTxs[hash].orEmpty() + TransactionHistoryItem(height, tx.txid)
        spend(script)
    }

    /** [tx] at the address it pays, confirmed at [height] (0 for the mempool), as a spend the wallet sent would be. */
    fun sent(tx: Transaction, height: Int) {
        val hash = scriptHash(tx.txOut.first().publicKeyScript)
        txs[tx.txid] = tx
        historyTxs[hash] = historyTxs[hash].orEmpty().filter { it.txid != tx.txid } + TransactionHistoryItem(height, tx.txid)
    }

    override suspend fun startScriptHashSubscription(scriptHash: ByteVector32): ScriptHashSubscriptionResponse {
        subscribed += scriptHash
        return ScriptHashSubscriptionResponse(scriptHash, status(scriptHash))
    }

    override suspend fun getScriptHashUnspents(scriptHash: ByteVector32): List<UnspentItem> = unspents[scriptHash].orEmpty()
    override suspend fun getTx(txId: TxId): Transaction? = txs[txId]
    override suspend fun getHeader(blockHeight: Int): BlockHeader? = null
    override suspend fun getHeaders(startHeight: Int, count: Int): List<BlockHeader> = emptyList()
    override suspend fun getMerkle(txId: TxId, blockHeight: Int, contextOpt: Transaction?): GetMerkleResponse? = null
    override suspend fun getScriptHashHistory(scriptHash: ByteVector32): List<TransactionHistoryItem> = historyTxs[scriptHash].orEmpty()
    override suspend fun broadcastTransaction(tx: Transaction): TxId = tx.txid
    override suspend fun estimateFees(confirmations: Int): FeeratePerKw? = null
    override suspend fun startHeaderSubscription() = HeaderSubscriptionResponse(tip, Block.RegtestGenesisBlock.header)

    companion object {
        /** Electrum's script hash: sha256 of the script, byte-reversed. */
        fun scriptHash(script: ByteVector): ByteVector32 = ByteVector32(Crypto.sha256(script.toByteArray()).reversedArray())
    }
}
