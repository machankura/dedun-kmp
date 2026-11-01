package com.machankura.dedun.compose.wallet.lightning

import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.managers.Depositors
import com.machankura.dedun.compose.managers.GroupLightningWalletManager
import com.machankura.dedun.compose.managers.GroupSwapInDerivation
import com.machankura.dedun.compose.managers.GroupWalletManager
import com.machankura.dedun.compose.nostr.wallet.GroupWalletStatementEvent
import com.machankura.dedun.compose.wallet.GroupChainClients
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The group swap-in wallets one identity is watching, one per group Lightning wallet, on the Electrum client
 * for its network: [com.machankura.dedun.compose.wallet.GroupOnchainWallets]'s counterpart, with the same
 * lifetime (`docs/group-lightning-swap-in.md`, Phase 3).
 *
 * Made on first use and kept, each asked again for the room's depositors every time it is handed out, so a
 * member opened since is watched from the next look.
 */
class GroupSwapInWallets(
    private val clients: GroupChainClients,
    private val scope: CoroutineScope,
    private val database: DedunDatabase,
) {
    private val mutex = Mutex()
    private val wallets = mutableMapOf<String, GroupSwapInWallet>()

    /**
     * [chatRoomId]'s swap-in wallet for [wallet], or null where it cannot have one: the room's FROST key is
     * not on this device, or the network's LSP publishes no swap-in key (regtest).
     */
    suspend fun walletFor(chatRoomId: String, wallet: GroupLightningWalletManager.Wallet): GroupSwapInWallet? {
        val statement = wallet.statement
        val xpub = GroupSwapInDerivation.lspSwapInXpub(statement.chain) ?: return null
        val roomKey = GroupWalletManager.thresholdKeyFor(database, chatRoomId) ?: return null
        val swapIn = mutex.withLock {
            wallets.getOrPut("$chatRoomId:${GroupWalletStatementEvent.chainName(statement.chain)}:${statement.wallet}") {
                val keys = GroupSwapInDerivation.keys(roomKey, statement.chain, statement.wallet, statement.icebergKey, statement.nodeId, xpub)
                GroupSwapInWallet(keys, clients.clientFor(statement.chain), scope)
            }
        }
        swapIn.watchDepositors(Depositors.statementsIn(database, chatRoomId, statement.chain).mapValues { (_, depositor) -> depositor.branch })
        return swapIn
    }

    suspend fun stop() = mutex.withLock {
        wallets.values.forEach { it.stop() }
        wallets.clear()
    }
}
