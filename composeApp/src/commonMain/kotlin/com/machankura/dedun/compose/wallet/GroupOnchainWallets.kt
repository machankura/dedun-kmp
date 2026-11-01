package com.machankura.dedun.compose.wallet

import com.machankura.dedun.compose.managers.GroupWalletDerivation
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.Chain
import com.machankura.dedun.compose.managers.GuardianManager
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.OutPoint
import fr.acinq.bitcoin.Transaction
import fr.acinq.bitcoin.TxId
import fr.acinq.bitcoin.byteVector32
import fr.acinq.lightning.blockchain.electrum.IElectrumClient
import fr.acinq.lightning.blockchain.fee.FeeratePerByte
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The group wallets one identity is watching, each on the Electrum client for its network
 * ([GroupChainClients]): the identity's own node for the network it runs on, a connection of its own
 * for any other.
 *
 * One per identity, made where the identity's node is, and stopped with it: a
 * profile switch is a restart, and a wallet left watching on the last profile's
 * connection would be a wallet nobody can see subscribing to addresses nobody
 * asked about. Wallets are made on first use -- opening a group's wallet screen
 * -- and kept for the life of the identity, because an Electrum subscription is
 * cheap and the first answer is slow.
 */
class GroupOnchainWallets(
    private val clients: GroupChainClients,
    private val scope: CoroutineScope,
) {
    /** Every network on [electrum]: what the tests, whose fake client answers anything, construct. */
    constructor(electrum: IElectrumClient, scope: CoroutineScope) : this(GroupChainClients.single(electrum), scope)

    private val mutex = Mutex()
    private val wallets = mutableMapOf<String, GroupOnchainWallet>()

    /**
     * The wallet watching [account], made the first time it is asked for. Keyed
     * by its receive descriptor, which names one account of one group on one
     * network *and* the guardians in its addresses -- an xpub alone would hand a
     * guarded account the plain one's addresses.
     */
    suspend fun walletFor(account: GroupWalletDerivation.Account): GroupOnchainWallet = mutex.withLock {
        wallets.getOrPut(account.receiveDescriptor) { GroupOnchainWallet(account, clients.clientFor(account.chain), scope) }
    }

    /**
     * A feerate to start the send form at, in sats per virtual byte: Electrum's
     * estimate for six blocks, or null when it has none. A suggestion the member
     * edits, never a value a spend is built on without them seeing it.
     */
    suspend fun suggestedFeeratePerVbyte(chain: Chain): Long? =
        clients.clientFor(chain).estimateFees(6)?.let { FeeratePerByte(it).feerate.sat }?.coerceAtLeast(1)

    /** Transactions fetched for [guardianSpends], which never change once mined or seen. */
    private val transactions = mutableMapOf<TxId, Transaction>()

    /**
     * Every spend a guardian of [state]'s account made from it, read off the
     * history of each address the wallet has used. Empty for an unguarded
     * account, and for whatever Electrum cannot answer right now -- the next look
     * asks again.
     *
     * History rather than a watch on coins disappearing: a device asleep when the
     * coin moved still finds it, since the chain keeps the record and nothing
     * here has to have been running at the time.
     */
    suspend fun guardianSpends(state: GroupWalletState): List<GuardianManager.Taken> {
        val account = state.account
        if (!account.isGuarded) return emptyList()
        val electrum = clients.clientFor(account.chain)

        val used = listOf(GroupWalletDerivation.RECEIVE to state.receive, GroupWalletDerivation.CHANGE to state.change)
            .flatMap { (branch, wallet) ->
                wallet.addresses.values.filter { it.alreadyUsed }.mapNotNull { address ->
                    address.meta.indexOrNull?.let { GroupWalletDerivation.derive(account, branch, it.toLong()) }
                }
            }
        val ours = used.associateBy { it.pubkeyScript }

        val history = used.flatMap { derived ->
            runCatching { electrum.getScriptHashHistory(Crypto.sha256(derived.pubkeyScript).byteVector32().reversed()) }
                .getOrDefault(emptyList())
                .map { it.txid }
        }.distinct().mapNotNull { txid -> transactionOf(txid, electrum) }

        val coins = history.flatMap { tx ->
            tx.txOut.mapIndexedNotNull { index, out -> ours[out.publicKeyScript]?.let { OutPoint(tx, index.toLong()) to (out to it) } }
        }.toMap()

        return history.flatMap { GuardianManager.takenIn(it, account, coins) }
    }

    private suspend fun transactionOf(txid: TxId, electrum: IElectrumClient): Transaction? =
        mutex.withLock { transactions[txid] }
            ?: runCatching { electrum.getTx(txid) }.getOrNull()?.also { tx -> mutex.withLock { transactions[txid] = tx } }

    /**
     * Hands [tx] to Electrum -- for a spend a member signed alone, which has no
     * session for `GroupTransactionWatcher` to follow. Electrum reports success
     * whatever the server said, so this is not evidence it reached the network:
     * [confirmationsOf] is.
     */
    suspend fun broadcast(tx: Transaction, chain: Chain) {
        clients.clientFor(chain).broadcastTransaction(tx)
    }

    /** How deep [tx] is on [chain]: 0 in the mempool, null while Electrum has not heard of it or cannot be asked. */
    suspend fun confirmationsOf(tx: Transaction, chain: Chain): Int? = runCatching { clients.clientFor(chain).getConfirmations(tx) }.getOrNull()

    /**
     * Whether [script] has ever been paid on [chain], or is one of [ownWallet]'s addresses: what a
     * member's onchain payout address is checked against before it is published, and again before
     * the group pays it. `docs/group-payout-destination.md`, D7.
     */
    suspend fun usageOf(chain: Chain, script: ByteVector, ownWallet: GroupWalletState? = null): UnusedAddressCheck.Usage =
        UnusedAddressCheck.check(clients.clientFor(chain), script, isTheGroups = { ownWallet?.derivationOf(it) != null })

    suspend fun stop() = mutex.withLock {
        wallets.values.forEach { it.stop() }
        wallets.clear()
    }
}
