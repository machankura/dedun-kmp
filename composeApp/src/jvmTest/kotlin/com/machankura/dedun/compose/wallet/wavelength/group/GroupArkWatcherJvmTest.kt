package com.machankura.dedun.compose.wallet.wavelength.group

import androidx.room3.Room
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.GroupArkBoarded
import com.machankura.dedun.compose.database.model.GroupArkFee
import com.machankura.dedun.compose.database.model.GroupArkPendingRound
import com.machankura.dedun.compose.database.model.GroupArkVtxo
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.managers.GroupArkCoSigner
import com.machankura.dedun.compose.managers.GroupLightningFixture
import com.machankura.dedun.compose.managers.GroupWavelengthWalletManager
import com.machankura.dedun.compose.wallet.GroupChainClients
import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.BlockHeader
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Satoshi
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a device believes about a round, from what its own connection to the chain says and nobody else. */
class GroupArkWatcherJvmTest {
    /** A connection as the real client keeps one: its status holds the height it began at, and only asking gets the tip. */
    private class FakeElectrum : IElectrumClient {
        var tip = CONNECTED_AT
        val unspents = mutableMapOf<ByteVector32, List<UnspentItem>>()
        val histories = mutableMapOf<ByteVector32, List<TransactionHistoryItem>>()
        val txs = mutableMapOf<TxId, Transaction>()

        override val notifications = MutableSharedFlow<ElectrumSubscriptionResponse>()
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
        override suspend fun startScriptHashSubscription(scriptHash: ByteVector32) = ScriptHashSubscriptionResponse(scriptHash, null)
        override suspend fun startHeaderSubscription() = HeaderSubscriptionResponse(tip, Block.RegtestGenesisBlock.header)
    }

    private companion object {
        const val CONNECTED_AT = 1_000
    }

    private val db: DedunDatabase = getRoomDatabase(Room.inMemoryDatabaseBuilder<DedunDatabase>())
    private val electrum = FakeElectrum()
    private val watcher = GroupArkWatcher(db, GroupChainClients.single(electrum), CoroutineScope(Dispatchers.Default))
    private val dao = db.groupArkDao()

    private val script = ByteVector("5120" + "11".repeat(32))
    private val funding = Transaction(2, listOf(TxIn(OutPoint(TxId(ByteVector32("aa".repeat(32))), 0), 0)), listOf(TxOut(Satoshi(1_000_000), script)), 0)
    private val commitment = spending("bb")
    private val conflict = spending("cc")
    private val key = "wavelength:room:regtest:0"
    private val oldCoin = "${"dd".repeat(32)}:0"
    private val newCoin = "${commitment.txid}:0"
    private val scriptHash = Crypto.sha256(script).byteVector32().reversed()

    private fun spending(marker: String) = Transaction(2, listOf(TxIn(OutPoint(funding, 0), 0)), listOf(TxOut(Satoshi(999_000), ByteVector("5120" + marker.repeat(32)))), 0)

    @AfterTest
    fun close() = db.close()

    /** A round this device has signed a forfeit and a tree node for, as far as the operator has told it. */
    private suspend fun signedRound(chain: Chain = Chain.Regtest) {
        val name = if (chain == Chain.Mainnet) "mainnet" else "regtest"
        dao.upsertPendingRound(GroupArkPendingRound(key, commitment.txid.toString(), "room", name, Hex.encode(Transaction.write(commitment))))
        dao.upsertVtxo(GroupArkVtxo(key, oldCoin, 40_000, settled = true, reservedBy = commitment.txid.toString(), term = 1))
        dao.upsertVtxo(GroupArkVtxo(key, newCoin, 39_000, origin = commitment.txid.toString(), term = 1, sweepDelay = 1_008))
        dao.upsertFee(GroupArkFee(key, commitment.txid.toString(), 1_000, 1, 5))
    }

    private suspend fun vtxo(outpoint: String) = dao.vtxos(key).firstOrNull { it.outpoint == outpoint }

    private fun heightOf(confirmations: Int) = if (confirmations > 0) electrum.tip - confirmations + 1 else 0

    /** [tx] as deep as [confirmations], at the address Electrum is asked about it at: 0 is the mempool. */
    private fun confirmed(tx: Transaction, confirmations: Int) {
        val at = Crypto.sha256(tx.txOut.first().publicKeyScript).byteVector32().reversed()
        electrum.histories[at] = listOf(TransactionHistoryItem(heightOf(confirmations), tx.txid))
    }

    private fun spentBy(tx: Transaction, confirmations: Int) {
        electrum.txs[funding.txid] = funding
        electrum.txs[tx.txid] = tx
        confirmed(tx, confirmations)
        electrum.unspents[scriptHash] = emptyList()
        electrum.histories[scriptHash] = listOf(TransactionHistoryItem(heightOf(confirmations), tx.txid))
    }

    @Test
    fun `a round nobody has seen confirm stays pending, whatever anyone says`() = runBlocking<Unit> {
        signedRound()
        watcher.pass()
        assertFalse(vtxo(newCoin)!!.settled)
        assertFalse(vtxo(oldCoin)!!.spent)
        confirmed(commitment, 0)
        watcher.pass()
        assertFalse(vtxo(newCoin)!!.settled, "the mempool isn't a block")
        assertEquals(1, dao.pendingRounds().size)
    }

    @Test
    fun `a round settles once confirmed, the new coin spendable and the old one gone`() = runBlocking<Unit> {
        signedRound()
        confirmed(commitment, 1)
        watcher.pass()
        assertTrue(vtxo(newCoin)!!.settled)
        assertTrue(vtxo(oldCoin)!!.spent)
        assertEquals(emptyList(), dao.pendingRounds(), "and nothing is waited on")
    }

    @Test
    fun `a coin can be swept from the height this device saw its commitment at, plus the batch's life`() = runBlocking<Unit> {
        signedRound()
        electrum.tip = CONNECTED_AT + 10
        confirmed(commitment, 2)
        watcher.pass()
        assertEquals(CONNECTED_AT + 10 - 2 + 1 + 1_008L, vtxo(newCoin)!!.expiresAtHeight)
        assertEquals(null, vtxo(oldCoin)!!.expiresAtHeight, "and what this device never worked out, it doesn't guess")
    }

    @Test
    fun `mainnet waits for three blocks`() = runBlocking<Unit> {
        signedRound(Chain.Mainnet)
        confirmed(commitment, 2)
        watcher.pass()
        assertFalse(vtxo(newCoin)!!.settled)
        confirmed(commitment, 3)
        watcher.pass()
        assertTrue(vtxo(newCoin)!!.settled)
    }

    @Test
    fun `a block newer than the connection still counts, though the client's own count stops where it connected`() = runBlocking<Unit> {
        signedRound()
        electrum.tip = CONNECTED_AT + 10
        confirmed(commitment, 1)
        assertEquals(-9, electrum.getConfirmations(commitment), "which is why the watcher doesn't ask it")
        watcher.pass()
        assertTrue(vtxo(newCoin)!!.settled)
    }

    @Test
    fun `a conflict in a block newer than the connection still counts`() = runBlocking<Unit> {
        signedRound()
        electrum.tip = CONNECTED_AT + 10
        spentBy(conflict, 1)
        watcher.pass()
        assertEquals(null, vtxo(oldCoin)!!.reservedBy)
        assertEquals(emptyList(), dao.pendingRounds())
    }

    @Test
    fun `a round that lost its input to a block is dead, so what it reserved is free and what it made never was`() = runBlocking<Unit> {
        signedRound()
        spentBy(conflict, 1)
        watcher.pass()
        assertEquals(null, vtxo(oldCoin)!!.reservedBy, "the coin can go into another round")
        assertFalse(vtxo(oldCoin)!!.spent)
        assertEquals(null, vtxo(newCoin), "and the coin it would have made never existed")
        assertEquals(emptyList(), dao.fees(key, 1, 0), "and it cost nothing")
        assertEquals(emptyList(), dao.pendingRounds())
    }

    @Test
    fun `a conflict still in the mempool settles nothing, since ours may yet confirm`() = runBlocking<Unit> {
        signedRound()
        spentBy(conflict, 0)
        watcher.pass()
        assertEquals(commitment.txid.toString(), vtxo(oldCoin)!!.reservedBy)
        assertEquals(1, dao.pendingRounds().size)
    }

    @Test
    fun `a conflict has to be as deep as a confirmation would`() = runBlocking<Unit> {
        signedRound(Chain.Mainnet)
        spentBy(conflict, 2)
        watcher.pass()
        assertEquals(commitment.txid.toString(), vtxo(oldCoin)!!.reservedBy)
        spentBy(conflict, 3)
        watcher.pass()
        assertEquals(null, vtxo(oldCoin)!!.reservedBy)
    }

    @Test
    fun `an input still unspent, or an input spent by the commitment itself, is no conflict`() = runBlocking<Unit> {
        signedRound()
        electrum.txs[funding.txid] = funding
        electrum.unspents[scriptHash] = listOf(UnspentItem(funding.txid, 0, 1_000_000, 90))
        watcher.pass()
        assertEquals(1, dao.pendingRounds().size)
        electrum.unspents[scriptHash] = emptyList()
        electrum.txs[commitment.txid] = commitment
        electrum.histories[scriptHash] = listOf(TransactionHistoryItem(100, commitment.txid))
        watcher.pass()
        assertEquals(1, dao.pendingRounds().size, "Electrum can know of a spend before it answers about the transaction")
    }

    @Test
    fun `one round's trouble doesn't stop the next`() = runBlocking<Unit> {
        signedRound()
        dao.upsertPendingRound(GroupArkPendingRound("wavelength:other:regtest:0", "ee".repeat(32), "other", "regtest", "not a transaction"))
        confirmed(commitment, 1)
        watcher.pass()
        assertTrue(vtxo(newCoin)!!.settled)
    }

    /** What the platform was asked to say, in order: title and text, as a member would read them. */
    private val said = mutableListOf<Pair<String, String>>()
    private val recording = GroupWavelengthAlerts { _, title, text -> said += title to text }

    /**
     * A real room, since a line in the transcript needs one.
     *
     * [withWallet] also opens a Wavelength wallet, which the **devices** trigger needs and the deadline
     * trigger does not: without a statement this device can read there is no threshold to compare a
     * quorum against, and `readyForARound` says yes rather than alarming about a wallet it cannot read.
     */
    private fun <T> inARoom(
        withWallet: Boolean = false,
        block: suspend (GroupLightningFixture, GroupLightningFixture.Device, GroupArkWatcher) -> T,
    ) = runBlocking<Unit> {
        said.clear()
        val fixture = GroupLightningFixture()
        try {
            val all = fixture.group()
            if (withWallet) {
                fixture.runCeremony(all, purpose = IcebergKeySession.PURPOSE_WAVELENGTH)
                val session = GroupWavelengthWalletManager.proposeWallet(
                    all[0].db, all[0].room, all[0].publicKey, all[0].privateKey, fixture.chain, "02" + "ab".repeat(32),
                )
                fixture.signSession(all, all[0], session.id)
            }
            val device = all[0]
            block(fixture, device, GroupArkWatcher(device.db, GroupChainClients.single(electrum), CoroutineScope(Dispatchers.Default), recording))
        } finally {
            fixture.close()
        }
    }

    private suspend fun coinExpiring(fixture: GroupLightningFixture, device: GroupLightningFixture.Device, atHeight: Long) =
        device.db.groupArkDao().upsertVtxo(GroupArkVtxo(GroupArkCoSigner.walletKey(fixture.roomId, fixture.chain, 0), "ee:0", 1_000, settled = true, expiresAtHeight = atHeight))

    @Test
    fun `a coin near its deadline gets one line per level, by this device's own count`() = inARoom { fixture, device, watching ->
        coinExpiring(fixture, device, CONNECTED_AT + 100L)
        watching.deadlinePass()
        watching.deadlinePass()
        val urgent = fixture.lines(device, ChatMessage.TYPE_WAVELENGTH_DEADLINE)
        assertEquals(1, urgent.size, "urgent, and only once")
        assertTrue("need renewing within" in urgent.single().content, urgent.single().content)
        electrum.tip = CONNECTED_AT + 101
        watching.deadlinePass()
        assertEquals(2, fixture.lines(device, ChatMessage.TYPE_WAVELENGTH_DEADLINE).size, "and again once it has passed")
    }

    @Test
    fun `a coin with plenty of time says nothing`() = inARoom { fixture, device, watching ->
        coinExpiring(fixture, device, CONNECTED_AT + 100_000L)
        watching.deadlinePass()
        assertEquals(0, fixture.lines(device, ChatMessage.TYPE_WAVELENGTH_DEADLINE).size)
    }


    // ---------------------------------------------------------------------
    // The two things worth interrupting a member for (B7).
    // ---------------------------------------------------------------------

    /**
     * The deadline trigger, and the de-duplication that comes with it for nothing: the alert fires only
     * where the room line was **new**, so the fact key is what stops a member being told twice.
     */
    @Test
    fun `a coin near its deadline is said once, out loud as well as in the room`() = inARoom { fixture, device, watching ->
        coinExpiring(fixture, device, CONNECTED_AT + 100L)
        watching.deadlinePass()
        watching.deadlinePass()
        assertEquals(1, said.size, "once per deadline per level, which is the fact key's doing")
        assertEquals("The group's coins need renewing", said.single().first)
        assertTrue("depends on the Ark operator" in said.single().second, said.single().second)
    }

    /** Past the deadline is a second level, and so a second thing to say. */
    @Test
    fun `passing the deadline is said again`() = inARoom { fixture, device, watching ->
        coinExpiring(fixture, device, CONNECTED_AT + 100L)
        watching.deadlinePass()
        electrum.tip = CONNECTED_AT + 101
        watching.deadlinePass()
        assertEquals(2, said.size)
    }

    /** A wallet in good order interrupts nobody, which is the case that keeps the channel worth reading. */
    @Test
    fun `a coin with plenty of time says nothing out loud`() = inARoom { fixture, device, watching ->
        coinExpiring(fixture, device, CONNECTED_AT + 100_000L)
        watching.deadlinePass()
        assertEquals(emptyList(), said)
    }

    /**
     * The devices trigger's own line and its own alert.
     *
     * The fixture's devices have never answered, so a round cannot be made. At nine days out the
     * deadline line is not written at all -- the operator's free window is 648 blocks -- which is
     * exactly why this trigger has a line of its own.
     */
    @Test
    fun `quiet devices with coins inside a fortnight are said, before the deadline line exists`() = inARoom(withWallet = true) { fixture, device, watching ->
        coinExpiring(fixture, device, CONNECTED_AT + 1_500L)
        watching.deadlinePass()
        assertEquals(0, fixture.lines(device, ChatMessage.TYPE_WAVELENGTH_DEADLINE).size, "nine days out is not a deadline line")
        val quiet = fixture.lines(device, ChatMessage.TYPE_WAVELENGTH_DEVICES_QUIET)
        assertEquals(1, quiet.size)
        assertTrue("Too few of the group's devices have answered" in quiet.single().content, quiet.single().content)
        assertEquals(1, said.size)
        assertEquals("The group's devices have gone quiet", said.single().first)
    }

    /** And said once, for the same reason the deadline is. */
    @Test
    fun `quiet devices are said once per deadline`() = inARoom(withWallet = true) { fixture, device, watching ->
        coinExpiring(fixture, device, CONNECTED_AT + 1_500L)
        watching.deadlinePass()
        watching.deadlinePass()
        assertEquals(1, said.size)
        assertEquals(1, fixture.lines(device, ChatMessage.TYPE_WAVELENGTH_DEVICES_QUIET).size)
    }

    /** Quiet devices with coins a month out are a line on the checklist, not an interruption. */
    @Test
    fun `quiet devices with coins well outside a fortnight say nothing`() = inARoom(withWallet = true) { fixture, device, watching ->
        coinExpiring(fixture, device, CONNECTED_AT + 5_000L)
        watching.deadlinePass()
        assertEquals(emptyList(), said)
        assertEquals(0, fixture.lines(device, ChatMessage.TYPE_WAVELENGTH_DEVICES_QUIET).size)
    }

    /** A wallet holding nothing has no deadline to miss. */
    @Test
    fun `a wallet with no coins says nothing`() = inARoom { _, _, watching ->
        watching.deadlinePass()
        assertEquals(emptyList(), said)
    }


    /**
     * A wallet whose statement this device cannot read alarms nobody about its devices.
     *
     * There is no threshold to compare a quorum against, so `readyForARound` says yes. Alarming would
     * mean telling a member their group has gone quiet on the strength of something this device has
     * never been able to read.
     */
    @Test
    fun `a wallet this device holds no statement for says nothing about its devices`() = inARoom { fixture, device, watching ->
        coinExpiring(fixture, device, CONNECTED_AT + 1_500L)
        watching.deadlinePass()
        assertEquals(emptyList(), said)
        assertEquals(0, fixture.lines(device, ChatMessage.TYPE_WAVELENGTH_DEVICES_QUIET).size)
    }

    private suspend fun boardedRound(amountSaid: Long, chatRoom: String = "room") {
        signedRound()
        electrum.txs[funding.txid] = funding
        dao.upsertBoarded(GroupArkBoarded(key, "${funding.txid}:0", commitment.txid.toString(), amountSaid, script.toByteArray().let { Hex.encode(it) }))
        confirmed(commitment, 1)
    }

    @Test
    fun `a boarded coin the chain doesn't bear out is dropped before the round can make it a deposit`() = runBlocking<Unit> {
        boardedRound(amountSaid = 999_999_999)
        watcher.pass()
        assertEquals(emptyList(), dao.boardedIn(key, commitment.txid.toString()), "the operator's word for what it held")
        assertTrue(vtxo(newCoin)!!.settled, "and the round still happened")
    }

    @Test
    fun `a boarded coin that's what it says goes on to be a deposit`() = runBlocking<Unit> {
        boardedRound(amountSaid = 1_000_000)
        watcher.pass()
        assertTrue(vtxo(newCoin)!!.settled)
        assertEquals(emptyList(), dao.boardedIn(key, commitment.txid.toString()), "filed once it happened, which leaves none waiting")
        assertEquals(1_000_000_000, db.depositDao().inRoom("room", "regtest").single().amountMsat)
    }

    @Test
    fun `a parent nobody can find yet means waiting, not believing and not refusing`() = runBlocking<Unit> {
        signedRound()
        dao.upsertBoarded(GroupArkBoarded(key, "${funding.txid}:0", commitment.txid.toString(), 1_000_000, "51"))
        confirmed(commitment, 1)
        watcher.pass()
        assertFalse(vtxo(newCoin)!!.settled)
        assertEquals(1, dao.pendingRounds().size)
    }
}
