package com.machankura.dedun.compose.wallet.wavelength.group

import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.GroupArkPendingRound
import com.machankura.dedun.compose.managers.GroupArkCoSigner
import com.machankura.dedun.compose.managers.GroupLightningLines
import com.machankura.dedun.compose.managers.GroupWavelengthWalletManager
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthEvents
import com.machankura.dedun.compose.text.BlockDuration
import com.machankura.dedun.compose.text.describe
import com.machankura.dedun.compose.wallet.wavelength.WalletExpiry
import com.machankura.dedun.compose.wallet.GroupChainClients
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.Transaction
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
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Sees for itself what became of each round this device signed for, on its own connection to the chain and never on the word of
 * whoever runs the wallet's daemon. A device signs for no VTXO it has only seen pending, so this is what lets a coin be spent at all.
 *
 * A round has happened once its commitment is [GroupWavelengthRollout.settleDepth] deep. It never will once a conflict is that deep:
 * each commitment spends the last one's change, so the next round conflicts with one that failed, usually within the half hour. A
 * commitment nobody has heard of and with no conflict is only waited on, however long, since a device can't prove it dead and the coin
 * it reserved is freed by an election if it never comes to anything.
 *
 * A transfer never reaches the chain, so isn't this: [GroupArkCoSigner] settles one when its checkpoints' signatures are complete.
 */
class GroupArkWatcher(
    private val database: DedunDatabase,
    private val clients: GroupChainClients,
    private val scope: CoroutineScope,
) {
    private val logger = Logger.withTag("GroupArkWatcher")
    private var job: Job? = null
    private var deadlines: Job? = null

    /** Runs a pass every [POLL] while any round is waited on. */
    fun start() {
        job?.cancel()
        job = scope.launch {
            database.groupArkDao().observePendingRounds().collectLatest { pending ->
                while (isActive && pending > 0) {
                    pass()
                    delay(POLL)
                }
            }
        }
        deadlines?.cancel()
        deadlines = scope.launch {
            while (isActive) {
                try {
                    deadlinePass()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logger.e("Could not look at the wallets' deadlines", e)
                }
                delay(DEADLINE_CHECK)
            }
        }
    }

    /**
     * Says in the room when a coin this device signed for is running out of time, by this device's own count and whatever the operator
     * says: an operator can let renewal slip, or lie about it, and that's when this matters (D11). Each level of each coin's deadline
     * gets one line, and it names who hasn't answered lately, since that's who can do something about it.
     */
    suspend fun deadlinePass() {
        database.groupArkDao().walletsWithCoins().forEach { key ->
            val roomId = key.split(":").getOrNull(1) ?: return@forEach
            val (chain, _) = GroupArkCoSigner.parseWalletKey(roomId, key) ?: return@forEach
            val earliest = database.groupArkDao().vtxos(key).filter { it.settled && !it.spent }.mapNotNull { it.expiresAtHeight }.minOrNull() ?: return@forEach
            val tip = clients.clientFor(chain).startHeaderSubscription().blockHeight.toLong()
            val free = GroupWavelengthTerms.of(chain)?.freeRefreshWindowBlocks ?: 0
            val expiry = WalletExpiry.ofHeight(earliest, tip, free)
            val level = when (expiry) {
                is WalletExpiry.Renewing -> "renewing"
                is WalletExpiry.Urgent -> "urgent"
                WalletExpiry.Expired -> "expired"
                else -> return@forEach
            }
            val reader = database.chatRoomDao().findChatRoomById(roomId)?.chatRoom?.userPublicKey ?: return@forEach
            val left = earliest - tip
            val when_ = if (left > 0) "within ${BlockDuration.describe(left)}" else "now, or the Ark operator can take them back"
            val quiet = quietSigners(roomId, key)
            GroupLightningLines.writeFact(
                database, roomId, ChatMessage.TYPE_WAVELENGTH_DEADLINE, "$key:$earliest:$level", reader,
                "Coins in the Wavelength wallet need renewing $when_." + if (quiet.isEmpty()) "" else " Not heard from lately: ${quiet.joinToString()}.",
                Clock.System.now(),
            )
        }
    }

    private suspend fun quietSigners(roomId: String, key: String): List<String> {
        val (chain, number) = GroupArkCoSigner.parseWalletKey(roomId, key) ?: return emptyList()
        val wallet = GroupWavelengthWalletManager.wallets(database, roomId, chain).firstOrNull { it.statement.wallet == number } ?: return emptyList()
        val seen = database.marmotInnerEventDao().newestBySender(roomId, GroupWavelengthEvents.CO_SIGNING.toList()).associate { it.publicKey.lowercase() to it.at }
        val since = Clock.System.now() - QUIET_AFTER
        return wallet.statement.participants.filter { (seen[it] ?: Instant.DISTANT_PAST) < since }.map { ChatMessage.memberName(database, it) }
    }

    fun stop() {
        job?.cancel()
        job = null
        deadlines?.cancel()
        deadlines = null
    }

    /** One look at every round still waited on. */
    suspend fun pass() {
        database.groupArkDao().pendingRounds().forEach { round ->
            try {
                follow(round)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.e("Could not follow round ${round.commitment}", e)
            }
        }
    }

    private suspend fun follow(round: GroupArkPendingRound) {
        val chain = GroupWalletStatementEvent.chainNamed(round.chain) ?: return
        val electrum = clients.clientFor(chain)
        val depth = GroupWavelengthRollout.settleDepth(chain)
        val commitment = Transaction.read(round.commitmentTx)
        // Null while Electrum hasn't heard of it, which is also what a dead commitment looks like.
        val standing = standingOf(electrum, commitment)
        when {
            standing == null -> if (conflicted(electrum, commitment, depth)) {
                logger.i { "Round ${round.commitment} lost to a conflict: its coins are free again" }
                GroupArkCoSigner.abandoned(database, round.wallet, round.commitment)
            }
            standing.confirmations >= depth -> GroupArkCoSigner.roundSettled(database, round, standing.height)
        }
    }

    /** Whether something other than [commitment] spent one of its inputs, at least [depth] deep. A conflict in the mempool settles nothing. */
    private suspend fun conflicted(electrum: IElectrumClient, commitment: Transaction, depth: Int): Boolean {
        commitment.txIn.forEach { input ->
            val parent = electrum.getTx(input.outPoint.txid) ?: return@forEach
            val script = parent.txOut.getOrNull(input.outPoint.index.toInt())?.publicKeyScript ?: return@forEach
            val scriptHash = scriptHashOf(script)
            val unspent = electrum.getScriptHashUnspents(scriptHash)
                .any { it.txid == input.outPoint.txid && it.outputIndex.toLong() == input.outPoint.index }
            if (unspent) return@forEach
            val found = electrum.getScriptHashHistory(scriptHash)
                .filter { it.blockHeight > 0 && it.txid != commitment.txid }
                .any { item ->
                    val spender = electrum.getTx(item.txid) ?: return@any false
                    spender.txIn.any { it.outPoint == input.outPoint } && (standingOf(electrum, spender)?.confirmations ?: 0) >= depth
                }
            if (found) return true
        }
        return false
    }

    /**
     * How deep [tx] is now: 0 in the mempool, null if Electrum hasn't heard of it. [IElectrumClient.getConfirmations] counts from the height
     * its connection began at, so a block newer than the connection never reaches any depth until the connection drops: a signet run sat
     * over an hour on a coin ten blocks deep. The tip is read last, so it is never behind the history it is measured against.
     */
    private suspend fun standingOf(electrum: IElectrumClient, tx: Transaction): Standing? {
        val item = electrum.getScriptHashHistory(scriptHashOf(tx.txOut.first().publicKeyScript)).find { it.txid == tx.txid } ?: return null
        val height = item.blockHeight.toLong()
        return if (height > 0) Standing(electrum.startHeaderSubscription().blockHeight - height + 1, height) else Standing(0, null)
    }

    /** How deep a transaction is, and the block it's in when it's in one. */
    private class Standing(val confirmations: Long, val height: Long?)

    private fun scriptHashOf(script: ByteVector) = Crypto.sha256(script).byteVector32().reversed()

    companion object {
        val POLL: Duration = 30.seconds

        /** Blocks are ten minutes apart, and this is about days. */
        val DEADLINE_CHECK: Duration = 30.minutes

        /** Long enough that a phone that's only been asleep overnight isn't named. */
        val QUIET_AFTER: Duration = 1.days
    }
}
