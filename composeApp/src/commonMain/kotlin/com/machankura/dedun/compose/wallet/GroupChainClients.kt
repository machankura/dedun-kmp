package com.machankura.dedun.compose.wallet

import fr.acinq.bitcoin.Chain
import fr.acinq.lightning.blockchain.electrum.IElectrumClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Electrum connection each network's group wallets watch through, for one identity.
 *
 * The identity has one for the network its node runs on -- the node's own, or for a profile with no
 * node the one `IdentityElectrum` gives it -- and every wallet on that network shares it. A group may
 * keep a wallet on another network as well -- testnet3, while its Lightning wallet is tried there
 * (`docs/group-lightning-wallet.md`, Phase 4) -- and that network gets a [StandaloneElectrum] of its
 * own, made the first time a wallet on it asks and kept for the identity's life, for the reason
 * [GroupOnchainWallets] keeps its wallets: a subscription is cheap and the first answer slow. Being the
 * same loop as the identity's own, it follows the profile's Tor setting.
 */
class GroupChainClients(
    private val nodeChain: Chain,
    private val nodeClient: IElectrumClient,
    private val connect: suspend (Chain) -> Connection,
) {
    /** A connection this object made, and how to close it. */
    class Connection(val client: IElectrumClient, val close: () -> Unit)

    private val mutex = Mutex()
    private val others = mutableMapOf<Chain, Connection>()

    suspend fun clientFor(chain: Chain): IElectrumClient =
        if (chain == nodeChain) nodeClient else mutex.withLock { others.getOrPut(chain) { connect(chain) }.client }

    suspend fun stop() = mutex.withLock {
        others.values.forEach { it.close() }
        others.clear()
    }

    companion object {
        /** Every network on one client: for a test, whose fake client answers for whatever it is asked. */
        fun single(client: IElectrumClient): GroupChainClients =
            GroupChainClients(Chain.Mainnet, client) { Connection(client) {} }

        /**
         * The identity's client for its own network -- its node's, or the one a profile with no node
         * is given -- and, for any other network a group keeps a wallet on, a [StandaloneElectrum]
         * from [standaloneFor]: the same connection loop the identity's own uses, so the same
         * server choice, the same pauses, and the same refusal of a clearnet server while Tor is on.
         */
        fun forNode(nodeChain: Chain, nodeClient: IElectrumClient, standaloneFor: (Chain) -> StandaloneElectrum): GroupChainClients =
            GroupChainClients(nodeChain, nodeClient) { chain ->
                val standalone = standaloneFor(chain).also { it.start() }
                Connection(standalone.client) { standalone.stop() }
            }
    }
}
