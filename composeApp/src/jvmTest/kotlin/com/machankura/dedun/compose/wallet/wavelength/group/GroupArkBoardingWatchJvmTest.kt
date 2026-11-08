package com.machankura.dedun.compose.wallet.wavelength.group

import androidx.room3.Room
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.DepositRow
import com.machankura.dedun.compose.database.model.GroupArkArriving
import com.machankura.dedun.compose.database.model.GroupArkBoarded
import com.machankura.dedun.compose.database.model.GroupArkDepositAddress
import com.machankura.dedun.compose.wallet.GroupChainClients
import fr.acinq.bitcoin.Bitcoin
import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
import fr.acinq.bitcoin.Script
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.TxIn
import fr.acinq.bitcoin.TxOut
import fr.acinq.bitcoin.byteVector32
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * What this device makes of a payment to one of its group Wavelength wallet's boarding addresses, on its own
 * connection to the chain: `docs/group-wavelength-unconfirmed-deposits.md`, Phase 2.
 *
 * Nothing here involves the operator. The address was checked when it was answered, and the amount, the block
 * and the disappearance all come off that address's own script hash.
 */
class GroupArkBoardingWatchJvmTest {

    /** A connection as the real client keeps one, with a status per script hash that a test can change. */
    private class FakeElectrum : IElectrumClient {
        var tip = CONNECTED_AT
        val unspents = mutableMapOf<ByteVector32, List<UnspentItem>>()
        val histories = mutableMapOf<ByteVector32, List<TransactionHistoryItem>>()
        val txs = mutableMapOf<TxId, Transaction>()

        /** What a subscription answers. Null is "this script has no history at all", which is a status and not a failure. */
        val statuses = mutableMapOf<ByteVector32, String?>()

        override val notifications = MutableSharedFlow<ElectrumSubscriptionResponse>(extraBufferCapacity = 16)
        override val connectionStatus: StateFlow<ElectrumConnectionStatus> =
            MutableStateFlow(ElectrumConnectionStatus.Connected(ServerVersionResponse("fake", "1.4"), CONNECTED_AT, Block.RegtestGenesisBlock.header))

        override suspend fun broadcastTransaction(tx: Transaction): TxId = tx.txid
        override suspend fun getTx(txId: TxId): Transaction? = txs[txId]
        override suspend fun getHeader(blockHeight: Int): BlockHeader? = null
        override suspend fun getHeaders(startHeight: Int, count: Int): List<BlockHeader> = emptyList()
        override suspend fun getMerkle(txId: TxId, blockHeight: Int, contextOpt: Transaction?): GetMerkleResponse? = null
        override suspend fun getScriptHashHistory(scriptHash: ByteVector32): List<TransactionHistoryItem> = histories[scriptHash].orEmpty()
        override suspend fun getScriptHashUnspents(scriptHash: ByteVector32): List<UnspentItem> = unspents[scriptHash].orEmpty()
        override suspend fun estimateFees(confirmations: Int): FeeratePerKw? = null
        override suspend fun startScriptHashSubscription(scriptHash: ByteVector32) =
            ScriptHashSubscriptionResponse(scriptHash, statuses[scriptHash])
        override suspend fun startHeaderSubscription() = HeaderSubscriptionResponse(tip, Block.RegtestGenesisBlock.header)

        /** Tells the wallet to look again, as a server does when a script's history changes. */
        suspend fun notify(scriptHash: ByteVector32, status: String?) {
            statuses[scriptHash] = status
            notifications.emit(ScriptHashSubscriptionResponse(scriptHash, status))
        }
    }

    private companion object {
        const val CONNECTED_AT = 1_000
        const val ROOM = "room"
        val CHAIN = Chain.Regtest
    }

    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
    private val electrum = FakeElectrum()
    private val scope = CoroutineScope(Dispatchers.Default)
    private val watch = GroupArkBoardingWatch(db, GroupChainClients.single(electrum), scope)
    private val dao = db.groupArkDao()

    private val key = "wavelength:$ROOM:regtest:0"
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)

    /** One boarding address of the group's, as a device holds it once it has recomputed the script. */
    private class Boarding(marker: String) {
        val script: ByteVector = ByteVector("5120" + marker.repeat(32))
        val address: String = Bitcoin.addressFromPublicKeyScript(CHAIN.chainHash, Script.parse(script)).right!!
        val scriptHash: ByteVector32 = Crypto.sha256(script).byteVector32().reversed()
    }

    private val aliceAddress = Boarding("11")
    private val bobAddress = Boarding("22")

    @AfterTest
    fun close() {
        watch.stop()
        scope.cancel()
        db.close()
    }

    private suspend fun answered(boarding: Boarding, member: String, requestId: String) {
        dao.insertDepositAddress(GroupArkDepositAddress(key, requestId, member, Instant.fromEpochSeconds(1)))
        dao.answerDepositAddress(key, requestId, boarding.address, boarding.script.toHex())
    }

    /** A payment to [boarding]: the transaction that made it, and the unspent it left. */
    private fun paid(boarding: Boarding, marker: String, amountSat: Long, height: Long): TxId {
        val tx = Transaction(
            version = 2,
            txIn = listOf(TxIn(OutPoint(TxId(ByteVector32(marker.repeat(32))), 0), 0)),
            txOut = listOf(TxOut(Satoshi(amountSat), boarding.script)),
            lockTime = 0,
        )
        electrum.txs[tx.txid] = tx
        electrum.unspents[boarding.scriptHash] = listOf(UnspentItem(tx.txid, 0, amountSat, height))
        electrum.histories[boarding.scriptHash] = listOf(TransactionHistoryItem(height.toInt(), tx.txid))
        electrum.statuses[boarding.scriptHash] = "paid"
        return tx.txid
    }

    private suspend fun <T : Any> eventually(what: String, check: suspend () -> T?): T = withTimeout(20.seconds) {
        var found: T? = null
        while (found == null) {
            found = check()
            if (found == null) delay(20)
        }
        found
    } ?: error(what)

    private suspend fun settle() = delay(300)

    @Test
    fun `a payment in the mempool becomes a row with no height, and whose it is`() = runBlocking {
        answered(aliceAddress, alice, "r1")
        val txid = paid(aliceAddress, "aa", 10_000, height = 0)

        watch.start()

        val row = eventually("a row for the mempool payment") { dao.arriving(key).singleOrNull() }
        assertEquals("$txid:0", row.outpoint)
        assertEquals(10_000, row.amountSat)
        assertEquals(alice, row.member)
        assertEquals(aliceAddress.script.toHex().lowercase(), row.script)
        assertNull(row.blockHeight, "in the mempool it has no block")
        assertTrue(row.firstSeenAtEpochSeconds > 0)
        assertNull(row.spentUnseenAtEpochSeconds)
    }

    @Test
    fun `a confirmation fills in the block and keeps the time it was first seen`() = runBlocking {
        answered(aliceAddress, alice, "r1")
        val txid = paid(aliceAddress, "aa", 10_000, height = 0)
        watch.start()
        val first = eventually("the mempool row") { dao.arriving(key).singleOrNull() }

        electrum.unspents[aliceAddress.scriptHash] = listOf(UnspentItem(txid, 0, 10_000, 990))
        electrum.histories[aliceAddress.scriptHash] = listOf(TransactionHistoryItem(990, txid))
        electrum.notify(aliceAddress.scriptHash, "confirmed")

        val row = eventually("the confirmed row") { dao.arriving(key).singleOrNull()?.takeIf { it.blockHeight != null } }
        assertEquals(990, row.blockHeight)
        assertEquals(first.firstSeenAtEpochSeconds, row.firstSeenAtEpochSeconds, "a later pass must not restamp it")
        // 1,000 connected, confirmed at 990: eleven deep, well past regtest's one.
        assertEquals(11, GroupArkBoardingWatch.depthOf(row, electrum.tip.toLong()))
        assertEquals(1, GroupArkBoardingWatch.confirmationsNeeded(CHAIN))
    }

    @Test
    fun `each member's own address, and a coin at it is theirs`() = runBlocking {
        answered(aliceAddress, alice, "r1")
        answered(bobAddress, bob, "r2")
        paid(aliceAddress, "aa", 10_000, height = 0)
        paid(bobAddress, "bb", 7_000, height = 980)

        watch.start()

        val rows = eventually("both rows") { dao.arriving(key).takeIf { it.size == 2 } }.associateBy { it.member }
        assertEquals(10_000, rows.getValue(alice).amountSat)
        assertEquals(7_000, rows.getValue(bob).amountSat)
        assertEquals(980, rows.getValue(bob).blockHeight)
    }

    @Test
    fun `an address the operator never answered is never watched`() = runBlocking {
        // Asked for, and no answer: no script, so nothing to subscribe to and no wallet to watch.
        dao.insertDepositAddress(GroupArkDepositAddress(key, "r1", alice, Instant.fromEpochSeconds(1)))

        watch.start()
        settle()

        assertEquals(emptyList(), dao.arriving(key))
    }

    @Test
    fun `a coin a round this device signed has taken in is handed over`() = runBlocking {
        answered(aliceAddress, alice, "r1")
        val txid = paid(aliceAddress, "aa", 10_000, height = 990)
        watch.start()
        eventually("the row") { dao.arriving(key).singleOrNull() }

        // This device co-signed the round, so it holds the boarding coin and will file the deposit itself.
        dao.upsertBoarded(GroupArkBoarded(key, "$txid:0", "commitment", 10_000, aliceAddress.script.toHex(), 2_000))
        // The round spent it: gone from the unspents, still in the address's history.
        electrum.unspents[aliceAddress.scriptHash] = emptyList()
        electrum.notify(aliceAddress.scriptHash, "spent")

        eventually("the row to be handed over") { dao.arriving(key).isEmpty().takeIf { it } }
        assertEquals(emptyList(), dao.arriving(key))
    }

    @Test
    fun `a coin already filed as a deposit is handed over`() = runBlocking {
        answered(aliceAddress, alice, "r1")
        val txid = paid(aliceAddress, "aa", 10_000, height = 990)
        watch.start()
        eventually("the row") { dao.arriving(key).singleOrNull() }

        db.depositDao().upsert(
            DepositRow(
                chatRoomId = ROOM,
                chain = "regtest",
                depositKey = "$txid:0",
                source = DepositRow.WAVELENGTH,
                amountMsat = 10_000_000,
                member = alice,
            )
        )
        electrum.unspents[aliceAddress.scriptHash] = emptyList()
        electrum.notify(aliceAddress.scriptHash, "spent")

        eventually("the row to be handed over") { dao.arriving(key).isEmpty().takeIf { it } }
        assertEquals(emptyList(), dao.arriving(key))
    }

    @Test
    fun `a coin taken into a round this device never signed is marked, not dropped`() = runBlocking {
        answered(aliceAddress, alice, "r1")
        paid(aliceAddress, "aa", 10_000, height = 990)
        watch.start()
        eventually("the row") { dao.arriving(key).singleOrNull() }

        // Spent, with the transaction still in the address's history, and no round of this device's behind it.
        electrum.unspents[aliceAddress.scriptHash] = emptyList()
        electrum.notify(aliceAddress.scriptHash, "spent")

        val row = eventually("the marked row") { dao.arriving(key).singleOrNull()?.takeIf { it.spentUnseenAtEpochSeconds != null } }
        assertEquals(10_000, row.amountSat, "the coin is still what it was")
    }

    @Test
    fun `a payment replaced before it confirmed is forgotten`() = runBlocking {
        answered(aliceAddress, alice, "r1")
        paid(aliceAddress, "aa", 10_000, height = 0)
        watch.start()
        eventually("the row") { dao.arriving(key).singleOrNull() }

        // Replaced: another transaction is at this address now, and the first is nowhere.
        val replacement = TxId(ByteVector32("cc".repeat(32)))
        electrum.unspents[aliceAddress.scriptHash] = emptyList()
        electrum.histories[aliceAddress.scriptHash] = listOf(TransactionHistoryItem(0, replacement))
        electrum.notify(aliceAddress.scriptHash, "replaced")

        eventually("the row to go") { dao.arriving(key).isEmpty().takeIf { it } }
        assertEquals(emptyList(), dao.arriving(key))
    }

    @Test
    fun `an address the subscription says has never been paid loses its row`() = runBlocking {
        answered(aliceAddress, alice, "r1")
        paid(aliceAddress, "aa", 10_000, height = 0)
        watch.start()
        eventually("the row") { dao.arriving(key).singleOrNull() }

        // A null status is the server saying this script has no history at all, which is an answer.
        electrum.unspents[aliceAddress.scriptHash] = emptyList()
        electrum.histories[aliceAddress.scriptHash] = emptyList()
        electrum.notify(aliceAddress.scriptHash, null)

        eventually("the row to go") { dao.arriving(key).isEmpty().takeIf { it } }
        assertEquals(emptyList(), dao.arriving(key))
    }

    @Test
    fun `a failed history call changes nothing`() = runBlocking {
        answered(aliceAddress, alice, "r1")
        paid(aliceAddress, "aa", 10_000, height = 990)
        watch.start()
        val before = eventually("the row") { dao.arriving(key).singleOrNull() }

        // The address is still paid -- the subscription says so -- but the history call comes back empty, which
        // lightning-kmp also does when the call failed. Forgetting the row on that would take the QR back up.
        electrum.unspents[aliceAddress.scriptHash] = emptyList()
        electrum.histories[aliceAddress.scriptHash] = emptyList()
        electrum.notify(aliceAddress.scriptHash, "still paid")
        settle()

        assertEquals(before, dao.arriving(key).singleOrNull(), "an empty history is not an answer")
    }

    @Test
    fun `nothing the watch does writes a deposit`() = runBlocking {
        answered(aliceAddress, alice, "r1")
        paid(aliceAddress, "aa", 50_000, height = 990)
        watch.start()
        eventually("the row") { dao.arriving(key).singleOrNull() }

        electrum.unspents[aliceAddress.scriptHash] = emptyList()
        electrum.notify(aliceAddress.scriptHash, "spent")
        settle()

        // A2: a DepositRow is a payable balance the moment it exists, and a boarding payment can still be replaced.
        assertTrue(db.depositDao().inRoom(ROOM, "regtest").isEmpty())
    }
}
