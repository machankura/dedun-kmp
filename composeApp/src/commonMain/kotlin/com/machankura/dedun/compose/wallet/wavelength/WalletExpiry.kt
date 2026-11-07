package com.machankura.dedun.compose.wallet.wavelength

import engineering.lightning.wavelength.model.WalletVTXO

/**
 * The earliest deadline on the wallet's coins, in blocks -- `docs/wavelength-wallet.md`, D10. The
 * daemon renews a coin for free once it is inside the operator's window, but only while it runs,
 * so what this says depends on the app being open often enough.
 */
sealed interface WalletExpiry {
    data object Nothing : WalletExpiry

    /** Not yet due: the daemon has no reason to do anything. */
    data class Fine(val blocksLeft: Long) : WalletExpiry

    /** In the operator's free window: the daemon renews it whenever it is running. */
    data class Renewing(val blocksLeft: Long) : WalletExpiry

    /** Close enough that a person has to act: open the app and leave it open. */
    data class Urgent(val blocksLeft: Long) : WalletExpiry

    /** Past it. The coin now depends on the operator, or on an exit. */
    data object Expired : WalletExpiry

    companion object {
        /** Two days. Below this the screen stops saying the app has it in hand. */
        const val URGENT_BLOCKS: Long = 288

        /** Coins in any other state are already on their way somewhere. */
        private const val LIVE = "live"

        fun of(coins: List<WalletVTXO>, tip: Long, freeWindowBlocks: Long): WalletExpiry =
            ofHeight(coins.filter { it.status == LIVE && it.batchExpiry > 0 }.minOfOrNull { it.batchExpiry.toLong() }, tip, freeWindowBlocks)

        /** From the soonest height a coin can be swept at, however that was worked out. */
        fun ofHeight(earliest: Long?, tip: Long, freeWindowBlocks: Long): WalletExpiry {
            earliest ?: return Nothing
            val left = earliest - tip
            return when {
                left <= 0 -> Expired
                left <= URGENT_BLOCKS -> Urgent(left)
                left <= freeWindowBlocks -> Renewing(left)
                else -> Fine(left)
            }
        }
    }
}
