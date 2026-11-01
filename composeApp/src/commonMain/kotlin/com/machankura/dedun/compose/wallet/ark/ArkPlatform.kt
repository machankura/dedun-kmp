package com.machankura.dedun.compose.wallet.ark

/**
 * Where a Bark library exists -- `docs/bark-wallet.md`, D4. Android and Linux x86-64
 * open through `barkMain`'s adapter; every other target says it cannot, before
 * anything native is loaded.
 */
expect object ArkPlatform {
    val supported: Boolean

    /** Opens, or on the first call creates, the wallet in [datadir]. Throws [ArkError]. */
    suspend fun open(config: ArkConfig, words: String, datadir: String): ArkWallet
}
