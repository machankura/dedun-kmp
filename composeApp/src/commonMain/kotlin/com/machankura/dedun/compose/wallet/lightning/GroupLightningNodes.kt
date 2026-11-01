package com.machankura.dedun.compose.wallet.lightning

import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.managers.GroupLightningNode
import com.machankura.dedun.compose.managers.GroupLightningOperatorManager
import com.machankura.dedun.compose.managers.GroupLightningWalletManager
import com.machankura.dedun.compose.managers.IcebergRitualManager
import com.machankura.dedun.compose.wallet.GroupLightningRollout
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The group Lightning nodes one identity runs: one for every wallet this device is the elected operator
 * of, started when it may run and stopped when it may not (`docs/group-lightning-wallet.md`, Phase 8).
 *
 * Made where the identity's node is and stopped with it, like the onchain wallets. Every [CHECK] it looks
 * again at every Lightning wallet this device holds a share of: a node starts once this device is elected
 * and has heard from the room recently, and each running node stops itself when a later term begins.
 *
 * The same check abandons any of this identity's Lightning key ceremonies that has passed its deadline
 * ([IcebergRitualManager.abandonOverdue]): it is the one loop an identity runs for its Lightning wallets,
 * and a ceremony nobody is looking at is exactly the one that needs it.
 *
 * On Android the node runs while the app does. A foreground service that keeps it running with the app
 * in the background is the next piece of work, and the nomination screen says so meanwhile.
 */
class GroupLightningNodes(
    private val database: DedunDatabase,
    private val userPublicKey: HexKey,
    private val scope: CoroutineScope,
    private val peerFactory: suspend (GroupLightningNode.NodeConfiguration) -> GroupLightningNode.PeerHandle,
    private val chain: Chain = GroupLightningRollout.NETWORK,
    private val minimumPool: Int = GroupLightningNode.MINIMUM_POOL,
) {
    private val logger = Logger.withTag("GroupLightningNodes")
    private val mutex = Mutex()
    private val nodes = mutableMapOf<String, GroupLightningNode>()
    private var job: Job? = null

    /** The node of [chatRoomId]'s wallet [wallet] -- its current one when null -- if this device runs it. */
    fun nodeFor(chatRoomId: String, wallet: Long? = null): GroupLightningNode? =
        if (wallet != null) nodes[key(chatRoomId, wallet)]
        else nodes.filterKeys { it.startsWith("$chatRoomId:") }.maxByOrNull { it.value.wallet.statement.wallet }?.value

    private fun key(chatRoomId: String, wallet: Long) = "$chatRoomId:$wallet"

    /**
     * Starts every node this device may run, and forgets the ones that stopped. After a re-key a room has
     * two wallets, each with its own operator: the old one runs until its channels are closed (Phase 14).
     */
    suspend fun refresh() = mutex.withLock {
        if (!GroupLightningRollout.ENABLED && chain != Chain.Regtest) return@withLock
        val rooms = database.icebergKeySessionDao().getCompletedSessions().filter { it.userPublicKey == userPublicKey }.map { it.chatRoomId }.toSet()
        for (roomId in rooms) {
            val localChatRoom = database.chatRoomDao().findChatRoomById(roomId) ?: continue
            for (wallet in GroupLightningWalletManager.wallets(database, roomId, chain)) {
                val key = key(roomId, wallet.statement.wallet)
                val operator = GroupLightningOperatorManager.current(database, roomId, chain, wallet.statement.wallet)
                val existing = nodes[key]
                if (existing?.state?.value is GroupLightningNode.State.Running) continue
                if (operator?.publicKey != userPublicKey) {
                    nodes.remove(key)
                    continue
                }
                val node = existing ?: GroupLightningNode(database, localChatRoom, userPublicKey, wallet, scope, peerFactory, minimumPool = minimumPool).also { nodes[key] = it }
                val state = runCatching { node.start() }.onFailure { logger.e("Could not start the node for $key", it) }.getOrNull()
                if (state is GroupLightningNode.State.Refused) logger.i("Not running the node for $key: ${state.reason}")
            }
        }
    }

    fun start() {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                runCatching { IcebergRitualManager.abandonOverdue(database, userPublicKey) }.onFailure { logger.e("Could not abandon overdue key ceremonies", it) }
                runCatching { refresh() }.onFailure { logger.e("Could not refresh the group nodes", it) }
                delay(CHECK)
            }
        }
    }

    suspend fun stop() {
        job?.cancel()
        mutex.withLock {
            nodes.values.forEach { runCatching { it.stop() } }
            nodes.clear()
        }
    }

    companion object {
        val CHECK: Duration = 1.minutes
    }
}
