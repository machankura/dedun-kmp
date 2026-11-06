package com.machankura.dedun.compose.wallet.wavelength.group

import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Satoshi

/**
 * The gates a group's Wavelength wallet ships behind -- `docs/group-wavelength-wallet.md`, D12 and Phase 14 -- each
 * with the condition that lifts it.
 *
 * Like the other wallets' gates they decide what this device offers, never what it accepts: a device with a gate shut
 * still verifies and co-signs for a wallet another member opened, except [MAINNET_CAP], which every co-signer holds to,
 * because it is the one gate that bounds what can be lost.
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
