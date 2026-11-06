package com.machankura.dedun.compose.wallet.wavelength.group

import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Satoshi

/**
 * The gates a group's Wavelength wallet ships behind -- `docs/group-wavelength-wallet.md`, D12 and Phase 14 -- each
 * with the condition that lifts it.
 *
 * [ENABLED], like the other wallets' gates, decides what this device offers and not what it accepts: with it shut, a device
 * still verifies and co-signs for a wallet another member opened. [allows] is stricter than theirs, because nobody has reviewed
 * this: a device neither opens nor believes a wallet on a chain it refuses, so it has nothing there to co-sign for, whatever build
 * another member runs. [MAINNET_CAP] is what every co-signer holds to once mainnet is allowed.
 */
object GroupWavelengthRollout {
    /**
     * Whether the group's screen offers this wallet, and whether this device runs the daemon it is elected to. Lifts when
     * a signet group has been through Phases 8 to 13 by hand. No coin has moved through it on any network.
     */
    const val ENABLED: Boolean = false

    /**
     * Mainnet stays refused here, in code, until somebody outside the project has reviewed the Iceberg module, the DKG,
     * a group signing alone, the hashed tree keys and the signing policy, and a signet group has run for a month with no
     * refused label, no unannounced missed renewal and no lost coin. The Iceberg library says not to put money behind it.
     */
    fun allows(chain: Chain): Boolean = chain == Chain.Signet || chain == Chain.Regtest

    /** The most the group's unspent coins may come to on mainnet, against each co-signer's own ledger. */
    val MAINNET_CAP: Satoshi = Satoshi(100_000)

    /** The most on [chain], or null where there is no cap. */
    fun capFor(chain: Chain): Satoshi? = MAINNET_CAP.takeIf { chain.isMainnet() }
}
