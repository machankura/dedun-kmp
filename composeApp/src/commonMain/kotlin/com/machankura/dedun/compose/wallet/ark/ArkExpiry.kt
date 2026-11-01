package com.machankura.dedun.compose.wallet.ark

/**
 * How soon the wallet's coins have to be renewed -- `docs/bark-wallet.md`, D12.
 *
 * A VTXO left past its expiry height can be swept by the server, after which getting
 * it back depends on the server's cooperation. The screens say the earliest deadline
 * in time, and inside the refresh threshold they say it as a warning.
 */
sealed interface ArkExpiry {
    /** No coin expires: the wallet is empty, or all of it is pending. */
    data object Nothing : ArkExpiry

    /** The earliest coin expires in [blocksLeft] blocks, outside the threshold. */
    data class Fine(val blocksLeft: Long) : ArkExpiry

    /** Inside the threshold: the app is renewing, or the user should. */
    data class RenewSoon(val blocksLeft: Long) : ArkExpiry

    /** Past expiry: the coins now depend on the server. */
    data object Expired : ArkExpiry

    companion object {
        /**
         * Bark's default `vtxo_refresh_expiry_threshold`: 144 blocks, a day, on mainnet,
         * and 12 elsewhere (`bark/src/config.rs`, `network_default`). The app warns from
         * the same point, so the warning and the daemon's own renewal start together.
         */
        fun thresholdFor(network: ArkNetwork): Long = if (network.isMainnet) 144 else 12

        fun of(firstExpiryHeight: Long?, tipHeight: Long, thresholdBlocks: Long): ArkExpiry {
            if (firstExpiryHeight == null) return Nothing
            val left = firstExpiryHeight - tipHeight
            return when {
                left <= 0 -> Expired
                left <= thresholdBlocks -> RenewSoon(left)
                else -> Fine(left)
            }
        }
    }
}
