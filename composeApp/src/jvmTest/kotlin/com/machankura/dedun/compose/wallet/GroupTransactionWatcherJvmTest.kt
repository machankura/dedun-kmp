package com.machankura.dedun.compose.wallet

import androidx.room3.Room
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.GroupTransaction
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.database.model.types.FrostSigningSubject
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.psbt.Psbt
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
import fr.acinq.secp256k1.Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * What happens to a signed spend after the group signed it: it is sent, sent
 * again while nobody has seen it, and -- once it confirms -- settles every other
 * spend of the same coins.
 */
class GroupTransactionWatcherJvmTest {
    /** A connection as the real client keeps one: its status holds the height it began at, and only asking gets the tip. */
    private class FakeElectrum : IElectrumClient {
        var tip = CONNECTED_AT
        val broadcasts = mutableListOf<TxId>()
        /** What each script hash still holds, has seen, and the transactions behind both. */
        val unspents = mutableMapOf<ByteVector32, List<UnspentItem>>()
        val histories = mutableMapOf<ByteVector32, List<TransactionHistoryItem>>()
        val txs = mutableMapOf<TxId, Transaction>()

        override val notifications = MutableSharedFlow<ElectrumSubscriptionResponse>()
        override val connectionStatus: StateFlow<ElectrumConnectionStatus> =
            MutableStateFlow(ElectrumConnectionStatus.Connected(ServerVersionResponse("fake", "1.4"), CONNECTED_AT, Block.RegtestGenesisBlock.header))
        override suspend fun broadcastTransaction(tx: Transaction): TxId = tx.txid.also { broadcasts += it }
        override suspend fun getTx(txId: TxId): Transaction? = txs[txId]
        override suspend fun getHeader(blockHeight: Int): BlockHeader? = null
        override suspend fun getHeaders(startHeight: Int, count: Int): List<BlockHeader> = emptyList()
        override suspend fun getMerkle(txId: TxId, blockHeight: Int, contextOpt: Transaction?): GetMerkleResponse? = null
        override suspend fun getScriptHashHistory(scriptHash: ByteVector32): List<TransactionHistoryItem> = histories[scriptHash].orEmpty()
        override suspend fun getScriptHashUnspents(scriptHash: ByteVector32): List<UnspentItem> = unspents[scriptHash].orEmpty()
        override suspend fun estimateFees(confirmations: Int): FeeratePerKw? = null
        override suspend fun startScriptHashSubscription(scriptHash: ByteVector32) = ScriptHashSubscriptionResponse(scriptHash, null)
        override suspend fun startHeaderSubscription() = HeaderSubscriptionResponse(tip, Block.RegtestGenesisBlock.header)
    }

    private companion object {
        const val CONNECTED_AT = 1_000
    }

    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
    private val electrum = FakeElectrum()
    private var clock = Instant.fromEpochSeconds(1_700_000_000)
    private val watcher = GroupTransactionWatcher(db, electrum, CoroutineScope(Dispatchers.Default), now = { clock })

    @AfterTest
    fun close() = db.close()

    private val user = KeyPair().pubKey.toHexKey()
    private val room = "11".repeat(32)

    /** The coin every spend below wants. */
    private val coin = OutPoint(TxId(ByteVector32(ByteArray(32) { 3 })), 0)

    private fun spending(vararg coins: OutPoint, pay: Long) = Transaction(
        2, coins.map { TxIn(it, ByteVector.empty, 0xfffffffdL) }, listOf(TxOut(Satoshi(pay), ByteVector("0014" + "00".repeat(20)))), 0,
    )

    /** [tx] at the address Electrum is asked about it at, confirmed at [height] (0 for the mempool). */
    private fun onChain(tx: Transaction, height: Int) {
        val at = ByteVector32(fr.acinq.bitcoin.Crypto.sha256(tx.txOut.first().publicKeyScript.toByteArray()).reversedArray())
        electrum.histories[at] = electrum.histories[at].orEmpty().filter { it.txid != tx.txid } + TransactionHistoryItem(height, tx.txid)
    }

    private suspend fun seed() {
        val eventId = "c".repeat(64)
        db.nostrEventDao().upsert(NostrEvent(id = eventId, pubKey = user, kind = 0, tags = emptyArray(), content = "{}", sig = "0".repeat(128)))
        db.profileDao().upsert(Profile(publicKey = user, userName = "user", nostrEventId = eventId))
        db.chatRoomDao().upsert(ChatRoom(id = room, userPublicKey = user, subject = null, description = null, mlsGroupState = "00"))
    }

    private suspend fun spend(id: String, tx: Transaction, stage: FrostSigningStage, signed: Boolean, chain: String = "regtest") {
        db.frostSigningSessionDao().upsert(
            FrostSigningSession(
                id = id, chatRoomId = room, coordinatorPublicKey = user, userPublicKey = user, dkgSessionId = "dkg",
                threshold = 2, participantCount = 3, signerId = 0, stage = stage, subject = FrostSigningSubject.TRANSACTION,
            )
        )
        db.groupTransactionDao().insertIfAbsent(
            GroupTransaction(
                frostSigningSessionId = id, chatRoomId = room, chain = chain, account = 0,
                psbt = GroupSpend.encode(Psbt(tx)), txid = tx.txid.toString(),
                signedTransaction = if (signed) Hex.encode(Transaction.write(tx)) else null,
            )
        )
    }

    @Test
    fun `each spend goes out on its own network's connection`() = runBlocking {
        // A group with wallets on two networks: the node's, and the test network a Lightning wallet is
        // tried on. Each signed spend must reach the network it spends on, and only that one.
        seed()
        val testnet = FakeElectrum()
        val clients = GroupChainClients(fr.acinq.bitcoin.Chain.Regtest, electrum) { GroupChainClients.Connection(testnet) {} }
        val watcher = GroupTransactionWatcher(db, clients, CoroutineScope(Dispatchers.Default), now = { clock })
        val onNode = spending(coin, pay = 9_000)
        val onTestnet = spending(OutPoint(TxId(ByteVector32(ByteArray(32) { 4 })), 0), pay = 8_000)
        spend("a", onNode, FrostSigningStage.COMPLETE, signed = true)
        spend("b", onTestnet, FrostSigningStage.COMPLETE, signed = true, chain = "testnet3")
        watcher.pass()
        assertEquals(listOf(onNode.txid), electrum.broadcasts)
        assertEquals(listOf(onTestnet.txid), testnet.broadcasts)
    }

    @Test
    fun `a spend on a network nobody named is left alone`() = runBlocking {
        seed()
        spend("a", spending(coin, pay = 9_000), FrostSigningStage.COMPLETE, signed = true, chain = "litecoin")
        watcher.pass()
        assertTrue(electrum.broadcasts.isEmpty())
    }

    @Test
    fun `a signed spend is sent, and sent again while nobody has seen it`() = runBlocking {
        seed()
        val tx = spending(coin, pay = 9_000)
        spend("a", tx, FrostSigningStage.COMPLETE, signed = true)

        watcher.pass()
        watcher.pass()
        assertEquals(listOf(tx.txid), electrum.broadcasts, "not again inside the interval")

        clock += 11.minutes
        watcher.pass()
        assertEquals(2, electrum.broadcasts.size, "sent again once the interval has passed, still unseen")

        onChain(tx, height = 0)
        watcher.pass()
        val seen = assertNotNull(db.groupTransactionDao().getBySessionId("a"))
        assertNotNull(seen.seenAt, "in the mempool is seen")
        assertNull(seen.confirmedAt)

        clock += 11.minutes
        watcher.pass()
        assertEquals(2, electrum.broadcasts.size, "a seen transaction is not sent again")
    }

    @Test
    fun `a confirmation settles every other spend of the same coins`() = runBlocking {
        seed()
        val winner = spending(coin, pay = 9_000)
        spend("winner", winner, FrostSigningStage.COMPLETE, signed = true)
        // A bump of the same coins, signed but beaten to the block.
        val replaced = spending(coin, pay = 8_000)
        spend("replaced", replaced, FrostSigningStage.COMPLETE, signed = true)
        // A third spend of the coin, still collecting signatures.
        spend("collecting", spending(coin, OutPoint(TxId(ByteVector32(ByteArray(32) { 4 })), 0), pay = 15_000), FrostSigningStage.COLLECTING_NONCES, signed = false)
        // And one of some other coin, which none of this touches.
        spend("unrelated", spending(OutPoint(TxId(ByteVector32(ByteArray(32) { 5 })), 1), pay = 1_000), FrostSigningStage.COLLECTING_NONCES, signed = false)

        onChain(winner, height = CONNECTED_AT)
        watcher.pass()

        assertNotNull(db.groupTransactionDao().getBySessionId("winner")?.confirmedAt)
        assertEquals(winner.txid.toString(), db.groupTransactionDao().getBySessionId("replaced")?.replacedByTxid)
        assertEquals(FrostSigningStage.COMPLETE, db.frostSigningSessionDao().getSessionById("replaced")?.stage, "a signed spend is not reported as failed")
        db.frostSigningSessionDao().getSessionById("collecting")!!.let {
            assertEquals(FrostSigningStage.FAILED, it.stage)
            assertEquals("Spent by another transaction", it.failureReason)
        }
        assertEquals(FrostSigningStage.COLLECTING_NONCES, db.frostSigningSessionDao().getSessionById("unrelated")?.stage)

        // Once, however many passes see it.
        watcher.pass()
        val lines = db.chatMessageDao().getChatMessagesByChatRoomId(room)
            .filter { it.chatMessage.messageType == ChatMessage.TYPE_GROUP_TRANSACTION_CONFIRMED }
        assertEquals(1, lines.size)
        assertTrue(db.groupTransactionDao().getUnsettled().isEmpty(), "neither signed spend is still on its way")
    }

    @Test
    fun `a spend mined after the connection opened is confirmed`() = runBlocking<Unit> {
        seed()
        val tx = spending(coin, pay = 9_000)
        spend("a", tx, FrostSigningStage.COMPLETE, signed = true)
        onChain(tx, height = 0)
        watcher.pass()
        assertNull(db.groupTransactionDao().getBySessionId("a")?.confirmedAt, "in the mempool")

        // Nothing drops the connection: its status still says the height it began at.
        electrum.tip = CONNECTED_AT + 10
        onChain(tx, height = CONNECTED_AT + 3)
        assertEquals(-2, electrum.getConfirmations(tx), "which is why the watcher doesn't ask it")

        watcher.pass()
        assertNotNull(db.groupTransactionDao().getBySessionId("a")?.confirmedAt)
    }

    // ---- Coins another room's transaction took ------------------------------

    /** The script the guarded coin sits at, and its Electrum script hash. */
    private val coinScript = ByteVector("5120" + "ab".repeat(32))
    private val coinHash = ByteVector32(fr.acinq.bitcoin.Crypto.sha256(coinScript.toByteArray()).reversedArray())

    /** A signed spend of [coin] whose PSBT names the output it spends, as every real proposal's does. */
    private suspend fun signedSpendOfCoin(id: String, tx: Transaction) {
        db.frostSigningSessionDao().upsert(
            FrostSigningSession(
                id = id, chatRoomId = room, coordinatorPublicKey = user, userPublicKey = user, dkgSessionId = "dkg",
                threshold = 2, participantCount = 3, signerId = 0, stage = FrostSigningStage.COMPLETE, subject = FrostSigningSubject.TRANSACTION,
            )
        )
        val psbt = Psbt(tx).updateWitnessInput(coin, TxOut(Satoshi(10_000), coinScript)).right!!
        db.groupTransactionDao().insertIfAbsent(
            GroupTransaction(
                frostSigningSessionId = id, chatRoomId = room, chain = "regtest", account = 0,
                psbt = GroupSpend.encode(psbt), txid = tx.txid.toString(), signedTransaction = Hex.encode(Transaction.write(tx)),
            )
        )
    }

    /** Somebody else -- a guardian, or the guarded group itself -- spends [coin], at [height] (0 for the mempool). */
    private fun takenElsewhere(height: Int): Transaction {
        val theirs = spending(coin, pay = 7_000).copy(txOut = listOf(TxOut(Satoshi(7_000), ByteVector("0014" + "11".repeat(20)))))
        electrum.txs[theirs.txid] = theirs
        electrum.histories[coinHash] = listOf(TransactionHistoryItem(90, coin.txid), TransactionHistoryItem(height, theirs.txid))
        return theirs
    }

    @Test
    fun `a spend whose coin another transaction took is replaced once that transaction confirms`(): Unit = runBlocking {
        seed()
        val ours = spending(coin, pay = 9_000)
        signedSpendOfCoin("ours", ours)
        // Another of this room's spends of the same coin, still collecting.
        spend("collecting", spending(coin, OutPoint(TxId(ByteVector32(ByteArray(32) { 6 })), 0), pay = 12_000), FrostSigningStage.COLLECTING_NONCES, signed = false)

        val theirs = takenElsewhere(height = 101)
        watcher.pass()

        assertEquals(theirs.txid.toString(), db.groupTransactionDao().getBySessionId("ours")?.replacedByTxid)
        assertEquals(FrostSigningStage.COMPLETE, db.frostSigningSessionDao().getSessionById("ours")?.stage, "it was signed, and stays so")
        assertEquals(FrostSigningStage.FAILED, db.frostSigningSessionDao().getSessionById("collecting")?.stage)
        assertTrue(db.groupTransactionDao().getUnsettled().isEmpty(), "nothing is left being sent again every ten minutes")
    }

    @Test
    fun `a coin taken only in the mempool, or not taken at all, settles nothing`(): Unit = runBlocking {
        seed()
        signedSpendOfCoin("ours", spending(coin, pay = 9_000))

        // Still unspent where it sits.
        electrum.unspents[coinHash] = listOf(UnspentItem(coin.txid, coin.index.toInt(), 10_000, 90))
        electrum.histories[coinHash] = listOf(TransactionHistoryItem(90, coin.txid))
        watcher.pass()
        assertNull(db.groupTransactionDao().getBySessionId("ours")?.replacedByTxid)

        // Spent by something unconfirmed: ours may yet be the one that confirms.
        electrum.unspents.remove(coinHash)
        takenElsewhere(height = 0)
        watcher.pass()
        assertNull(db.groupTransactionDao().getBySessionId("ours")?.replacedByTxid)
    }
}
