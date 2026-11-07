package com.machankura.dedun.compose.wallet.wavelength.group

import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Satoshi

/**
 * The gates a group's Wavelength wallet ships behind -- `docs/group-wavelength-wallet.md`, D12 and Phase 14 -- each
 * with the condition that lifts it.
 *
 * [ENABLED], like the other wallets' gates, decides what this device offers and not what it accepts: with it shut, a device
 * still verifies and co-signs for a wallet another member opened. [allows] is stricter than theirs: a device neither opens nor
 * believes a wallet on a chain it refuses, so it has nothing there to co-sign for, whatever build another member runs.
 * [MAINNET_CAP] is what every co-signer holds to on mainnet.
 */
object GroupWavelengthRollout {
    /**
     * Whether the group's screen offers this wallet, and whether this device runs the daemon it is elected to. Lifts when
     * a signet group has been through Phases 8 to 13 by hand. No coin has moved through it on any network.
     */
    const val ENABLED: Boolean = false

    /**
     * The chains a wallet may be on. Mainnet by decision on 6 November 2026, ahead of what the plan named for it: a review
     * outside the project of the Iceberg module, the DKG, a group signing alone, the hashed tree keys and the signing policy,
     * and a month of a signet group with no refused label, no unannounced missed renewal and no lost coin. Neither has
     * happened, and the Iceberg library itself says not to put money behind it. What bounds the loss meanwhile is
     * [MAINNET_CAP] and every co-signer's checks. Testnets stay refused: nothing has run there and no operator is known.
     */
    fun allows(chain: Chain): Boolean = chain == Chain.Mainnet || chain == Chain.Signet || chain == Chain.Regtest

    /** The networks a group's screen offers a wallet on, each with a row of its own: both by decision, and regtest only for tests. */
    val NETWORKS: List<Chain> = listOf(Chain.Mainnet, Chain.Signet)

    /** The most the group's unspent coins may come to on mainnet, against each co-signer's own ledger. */
    val MAINNET_CAP: Satoshi = Satoshi(100_000)

    /**
     * How deep a commitment has to be before a device believes its round happened, or that a conflict killed it. A reorg past this
     * leaves a device's record wrong until an election, which costs the group its coins' use and not the coins: the operator's own
     * terms on mainnet ask for 1, in November 2026, and a little more costs only a wait before a new coin can be spent.
     */
    fun settleDepth(chain: Chain): Int = if (chain.isMainnet()) 3 else 1

    /** The most on [chain], or null where there is no cap. */
    fun capFor(chain: Chain): Satoshi? = MAINNET_CAP.takeIf { chain.isMainnet() }
}
