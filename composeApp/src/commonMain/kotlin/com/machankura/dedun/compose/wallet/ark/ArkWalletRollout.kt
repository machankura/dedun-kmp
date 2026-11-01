package com.machankura.dedun.compose.wallet.ark

import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Satoshi

/** The networks an Ark wallet can be opened on. Mapped to Bark's own enum only inside the adapters. */
enum class ArkNetwork {
    Mainnet,
    Signet,
    Regtest;

    val isMainnet: Boolean get() = this == Mainnet

    /**
     * Whether this network is [chain]: a group wallet on another network cannot be paid
     * from, or pay into, this one -- Phase 10.
     */
    fun matches(chain: Chain): Boolean = when (this) {
        Mainnet -> chain == Chain.Mainnet
        Signet -> chain == Chain.Signet
        Regtest -> chain == Chain.Regtest
    }

    /** The datadir's path segment: `<appFiles>/ark/<segment>/<walletId>` -- `docs/bark-wallet.md`, D2. */
    val segment: String get() = name.lowercase()
}

/** An Ark server and the Esplora the Bark SDK reads the chain through -- D7. */
data class ArkEndpoints(val server: String, val esplora: String)

/**
 * The gates the profile's Ark wallet ships behind -- `docs/bark-wallet.md`, D5 -- in
 * one place, each with the condition that lifts it.
 *
 * Its network is its own and not `NodeParamsManager.chain`: the Ark wallet shares
 * no LSP with the profile's node, and is on signet while the node is on mainnet.
 */
object ArkWalletRollout {
    /** Whether a seed profile is offered an Ark wallet at all. */
    const val ENABLED: Boolean = true

    /**
     * The network every profile's Ark wallet is opened on. Signet until Phase 12's
     * regtest run passes and one mainnet receive, send and renewal have been made by hand.
     */
    val NETWORK: ArkNetwork = ArkNetwork.Signet

    /**
     * The most one send may move, and the balance an invoice or amount request may
     * take the wallet to, on mainnet. The group wallets' figure: lifts when a release
     * has run real payments end to end. An Ark or onchain payment somebody simply
     * sends cannot be bounded, so the wallet screen says what the cap is.
     */
    val MAINNET_CAP: Satoshi = Satoshi(100_000)

    fun capFor(network: ArkNetwork): Satoshi? = MAINNET_CAP.takeIf { network.isMainnet }

    /**
     * Second's public servers -- https://second.tech/docs/connection-details.md.
     * Regtest is the harness in `testing/regtest/`, Phase 12.
     */
    fun endpointsFor(network: ArkNetwork): ArkEndpoints = when (network) {
        ArkNetwork.Mainnet -> ArkEndpoints("https://ark.second.tech", "https://mempool.second.tech/api")
        ArkNetwork.Signet -> ArkEndpoints("https://ark.signet.2nd.dev", "https://esplora.signet.2nd.dev")
        ArkNetwork.Regtest -> ArkEndpoints("http://127.0.0.1:3535", "http://127.0.0.1:3002")
    }

    /**
     * Whether receiving [incoming] would take [balance] past the cap on [network].
     * An amount the request does not name is not checked: the payer chooses it.
     */
    fun wouldExceedCap(network: ArkNetwork, balance: Satoshi, incoming: Satoshi?): Boolean {
        val cap = capFor(network) ?: return false
        return incoming != null && balance + incoming > cap
    }

    /** Whether a send of [amount] is over the cap on [network]. */
    fun sendExceedsCap(network: ArkNetwork, amount: Satoshi): Boolean {
        val cap = capFor(network) ?: return false
        return amount > cap
    }
}
