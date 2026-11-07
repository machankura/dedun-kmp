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
     * Whether the group's screen offers this wallet, and whether this device runs the daemon it is elected to. On by decision on
     * 7 November 2026, ahead of what the plan named for it: a signet group through Phases 8 to 13 by hand, and a real round on real
     * phones. Neither has happened: no phone has co-signed, and no screen or runner has met a real daemon or a coin.
     */
    const val ENABLED: Boolean = true

    /**
     * The chains a wallet may be on. Mainnet by decision on 6 November 2026, ahead of what the plan named for it: a review
     * outside the project of the Iceberg module, the DKG, a group signing alone, the hashed tree keys and the signing policy,
     * and a month of a signet group with no refused label, no unannounced missed renewal and no lost coin. Neither has
     * happened, and the Iceberg library itself says not to put money behind it. What bounds the loss meanwhile is
     * [MAINNET_CAP] and every co-signer's checks. Testnets stay refused: nothing has run there and no operator is known.
     */
    fun allows(chain: Chain): Boolean = chain == Chain.Mainnet || chain == Chain.Signet || chain == Chain.Regtest

    /**
     * The networks a group's screen offers a wallet on, each with a row of its own: mainnet alone, by decision on 7 November 2026.
     * [allows] still takes signet, so a device believes and co-signs for a signet wallet, but nothing here opens or runs one.
     */
    val NETWORKS: List<Chain> = listOf(Chain.Mainnet)

    /** The most the group's unspent coins may come to on mainnet, against each co-signer's own ledger. */
    val MAINNET_CAP: Satoshi = Satoshi(100_000)

    /**
     * Whether a payout goes out over Lightning from a wallet on [chain]. On mainnet by decision on 7 November 2026, ahead of what the plan
     * named for it: a failed payment's refund run on a real operator (`docs/group-wavelength-payouts.md`, W13). It has only met fixtures,
     * and a funded vHTLC the swap server abandons comes back only by the daemon's on-chain recovery, which has never run either. What bounds
     * a loss is one member's balance and [MAINNET_CAP].
     */
    fun paysOverLightning(chain: Chain): Boolean = true

    /**
     * Whether a payout goes out onchain, as a leave in a round, from a wallet on [chain]. On mainnet by decision on 7 November 2026, ahead
     * of what the plan named for it: one paid on signet, and the operator's onchain terms read (`docs/group-wavelength-payouts.md`, W13).
     * No payout leave has been in a real round. A device co-signs one whatever this says.
     */
    fun paysOnchain(chain: Chain): Boolean = true

    /**
     * The least an onchain payout offers to send. A stand-in until the operator's own onchain terms are read (Phase 0 of
     * `docs/group-wavelength-payouts.md`): mainnet's operator asked a minimum fee of 1,000 sats for a round, and less than that arriving
     * would lose most of itself to it.
     */
    const val MIN_ONCHAIN_PAYOUT_SAT: Long = 1_000

    /**
     * Whether the wallet offers to leave without the Ark operator's help on [chain]. A unilateral exit has never run on any network, so
     * not on mainnet until one has, on signet, where this is what lets someone run it.
     */
    fun offersUnilateralExit(chain: Chain): Boolean = !chain.isMainnet()

    /** How long a payment may tie its value up if it fails. A week: the server's real refund locktimes have only been seen on signet. */
    const val MAX_REFUND_BLOCKS: Long = 1_008

    /**
     * How deep a commitment has to be before a device believes its round happened, or that a conflict killed it. A reorg past this
     * leaves a device's record wrong until an election, which costs the group its coins' use and not the coins: the operator's own
     * terms on mainnet ask for 1, in November 2026, and a little more costs only a wait before a new coin can be spent.
     */
    fun settleDepth(chain: Chain): Int = if (chain.isMainnet()) 3 else 1

    /** The most on [chain], or null where there is no cap. */
    fun capFor(chain: Chain): Satoshi? = MAINNET_CAP.takeIf { chain.isMainnet() }
}
