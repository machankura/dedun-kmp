package com.machankura.dedun.compose.wallet

import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.GroupTransaction
import com.machankura.dedun.compose.managers.FrostSigningManager
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.byteVector32
import fr.acinq.lightning.blockchain.electrum.IElectrumClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Sends the group's signed transactions to the network, and watches for them.
 *
 * Every member with an Electrum client broadcasts. The transaction is identical
 * on every device, so a second broadcast is nothing to the network, and nobody's
 * phone being asleep holds up the group -- unlike a group event, whose sending is
 * a separate decision, the quorum has already decided the one thing broadcasting
 * does.
 *
 * Electrum's broadcast reports success whatever the server said, so a broadcast
 * is recorded when it was sent and nothing more; [GroupTransaction.seenAt] is
 * written only when Electrum reports the transaction back, and an unseen one is
 * sent again every [REBROADCAST_AFTER], which is what `IElectrumClient` asks of
 * its callers. A confirmation goes through [FrostSigningManager.recordConfirmed],
 * which settles every other spend of the same coins.
 *
 * A spend Electrum has not heard of may have lost its coins. Only the group can
 * spend a plain wallet's coins, so there the loser is always another of the
 * room's own spends and the winner's confirmation settles it. A guarded wallet's
 * coins have a second spender, in another room: so an unseen spend asks the
 * chain whether its coins are still there, and one taken by a *confirmed*
 * transaction it is not goes through [FrostSigningManager.recordReplaced]. A
 * conflict still in the mempool settles nothing -- ours may yet be the one that
 * confirms.
 */
class GroupTransactionWatcher(
    private val database: DedunDatabase,
    private val clients: GroupChainClients,
    private val scope: CoroutineScope,
    private val now: () -> Instant = { Clock.System.now() },
) {
    /** Every network on [electrum]: what the tests construct. */
    constructor(database: DedunDatabase, electrum: IElectrumClient, scope: CoroutineScope, now: () -> Instant = { Clock.System.now() }) :
        this(database, GroupChainClients.single(electrum), scope, now)

    /** The client for [transaction]'s own network, which a statement named and the row kept. */
    private suspend fun electrumFor(transaction: GroupTransaction): IElectrumClient? =
        GroupWalletStatementEvent.chainNamed(transaction.chain)?.let { clients.clientFor(it) }

    private val logger = Logger.withTag("GroupTransactionWatcher")
    private var job: Job? = null

    /** Runs a pass whenever a transaction is signed, and every [POLL] while any is on its way. */
    fun start() {
        job?.cancel()
        job = scope.launch {
            database.groupTransactionDao().observeUnsettled().collectLatest { unsettled ->
                while (isActive && unsettled.isNotEmpty()) {
                    pass()
                    delay(POLL)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** One look at every transaction still on its way. */
    suspend fun pass() {
        database.groupTransactionDao().getUnsettled().forEach { transaction ->
            try {
                follow(transaction)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.e("Could not follow transaction ${transaction.txid}", e)
            }
        }
    }

    /** Sends [sessionId]'s transaction again now, whatever was last sent. False when there is nothing signed to send. */
    suspend fun broadcastAgain(sessionId: String): Boolean {
        val transaction = database.groupTransactionDao().getBySessionId(sessionId) ?: return false
        val signed = transaction.signedTransaction?.let { Transaction.read(it) } ?: return false
        val electrum = electrumFor(transaction) ?: return false
        electrum.broadcastTransaction(signed)
        database.groupTransactionDao().upsert(transaction.copy(broadcastAt = now()))
        return true
    }

    private suspend fun follow(listed: GroupTransaction) {
        // Read again rather than taken from the list the pass started with: a
        // confirmation earlier in the same pass settles every other spend of its
        // coins, and writing this row back from the stale copy would undo that.
        val transaction = database.groupTransactionDao().getBySessionId(listed.frostSigningSessionId) ?: return
        if (transaction.confirmedAt != null || transaction.replacedByTxid != null) return
        val signed = Transaction.read(transaction.signedTransaction ?: return)
        val electrum = electrumFor(transaction) ?: return
        var row = transaction

        val due = row.broadcastAt?.let { now() - it >= REBROADCAST_AFTER } ?: true
        if (row.seenAt == null && due) {
            electrum.broadcastTransaction(signed)
            row = row.copy(broadcastAt = now())
            database.groupTransactionDao().upsert(row)
        }

        // Null while disconnected, or while Electrum has not heard of it -- which
        // is also what a spend whose coins somebody else took looks like.
        val confirmations = electrum.confirmationsOf(signed) ?: run {
            conflictOf(row, signed)?.let { FrostSigningManager.recordReplaced(database, row.frostSigningSessionId, it.toString()) }
            return
        }
        if (row.seenAt == null) {
            row = row.copy(seenAt = now())
            database.groupTransactionDao().upsert(row)
        }
        if (confirmations >= 1) {
            FrostSigningManager.recordConfirmed(database, row.frostSigningSessionId, now())
        }
    }

    /**
     * A confirmed transaction other than [signed] that spent one of its coins, or
     * null when every coin is still unspent, or was only taken by something
     * unconfirmed, or the chain cannot be asked right now.
     *
     * Read per coin off the address it is at: is it still unspent there, and if
     * not, which transaction in that address's history spent it. The address is
     * the spent output's script, which the proposed PSBT carries for every input.
     */
    private suspend fun conflictOf(transaction: GroupTransaction, signed: Transaction): TxId? {
        val psbt = GroupSpend.decode(transaction.psbt) ?: return null
        val electrum = electrumFor(transaction) ?: return null

        signed.txIn.forEachIndexed { index, input ->
            val txOut = (psbt.inputs.getOrNull(index) as? fr.acinq.bitcoin.psbt.Input.WitnessInput)?.txOut ?: return@forEachIndexed
            val scriptHash = Crypto.sha256(txOut.publicKeyScript).byteVector32().reversed()

            val unspent = electrum.getScriptHashUnspents(scriptHash)
                .any { it.txid == input.outPoint.txid && it.outputIndex.toLong() == input.outPoint.index }
            if (unspent) return@forEachIndexed

            electrum.getScriptHashHistory(scriptHash)
                .filter { it.blockHeight > 0 && it.txid != signed.txid }
                .firstOrNull { item -> electrum.getTx(item.txid)?.txIn?.any { it.outPoint == input.outPoint } == true }
                ?.let { return it.txid }
        }
        return null
    }

    companion object {
        val POLL: Duration = 30.seconds
        val REBROADCAST_AFTER: Duration = 10.minutes
    }
}
