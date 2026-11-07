package com.machankura.dedun.compose.wallet.wavelength.group

import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthWalletStatementEvent
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain

/**
 * What each network's Ark operator said of itself, as read from it on 7 November 2026. A wallet statement fixes the operator's key
 * and delays, so a change at the operator needs the group to agree rather than the daemon to quietly follow it, and this is what a
 * proposer's statement names. What a member reads on a screen about minimums comes from here too.
 *
 * A proposer's device reads the same thing again now, from [infoUrl], so that it isn't about to open a wallet every co-signer would
 * refuse every round of. That read is only ever a reason not to propose: nothing a device signs depends on it.
 */
object GroupWavelengthTerms {
    data class Terms(
        val operatorKey: HexKey,
        val exitDelay: Long,
        val boardingDelay: Long,
        /** Confirmations a boarding output needs before it can join a round. */
        val boardingConfirmations: Int,
        val minBoardingSat: Long,
        val minOperatorFeeSat: Long,
        val maxVtxoSat: Long,
        val maxWalletSat: Long,
        /** How long before a coin's expiry a renewal is free, in blocks. */
        val freeRefreshWindowBlocks: Long,
    ) {
        /** Whether [other] would need a new statement: the key or a delay every co-signer holds the operator to. */
        fun bindsTheSameAs(other: Terms): Boolean =
            operatorKey == other.operatorKey && exitDelay == other.exitDelay && boardingDelay == other.boardingDelay

        /** The policy a statement proposes under these terms. */
        fun policy(): GroupWavelengthWalletStatementEvent.Policy =
            GroupWavelengthWalletStatementEvent.Policy.DEFAULT.copy(exitDelay = exitDelay, boardingDelay = boardingDelay)
    }

    private val MAINNET = Terms(
        operatorKey = "0319b52a2d5673e58f93bad3d5ba59d85565f258bc3da9087b7659e67dcc229b17",
        exitDelay = 144,
        boardingDelay = 512,
        boardingConfirmations = 6,
        minBoardingSat = 1_000,
        minOperatorFeeSat = 1_000,
        maxVtxoSat = 50_000,
        maxWalletSat = 300_000,
        freeRefreshWindowBlocks = 648,
    )

    private val SIGNET = Terms(
        operatorKey = "037d362cf53f5fe3ab752974e2b8008302359cf7ae42a5f316c72f9044508f30b9",
        exitDelay = 144,
        boardingDelay = 512,
        boardingConfirmations = 1,
        minBoardingSat = 1_000,
        minOperatorFeeSat = 1_000,
        maxVtxoSat = 200_000,
        maxWalletSat = 100_000_000,
        freeRefreshWindowBlocks = 504,
    )

    /** The terms this build knows for [chain], or null where there is no public operator. */
    fun of(chain: Chain): Terms? = when (chain) {
        Chain.Mainnet -> MAINNET
        Chain.Signet -> SIGNET
        else -> null
    }

    /** Where [chain]'s operator answers `get-info`, or null where there is no public one. */
    fun infoUrl(chain: Chain): String? = when (chain) {
        Chain.Mainnet -> "https://wavelength-rest.lightning.finance/v1/ark/get-info"
        Chain.Signet -> "https://signet.wavelength-rest.lightning.finance/v1/ark/get-info"
        else -> null
    }
}
