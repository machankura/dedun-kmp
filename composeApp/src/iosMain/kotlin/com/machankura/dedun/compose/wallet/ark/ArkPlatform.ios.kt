package com.machankura.dedun.compose.wallet.ark

/**
 * Bark has no Kotlin/Native binding: its Swift package is reached through a bridge the
 * iOS app registers -- `docs/bark-wallet.md`, Phase 8. Until then, not on this device.
 */
actual object ArkPlatform {
    actual val supported: Boolean = false

    actual suspend fun open(config: ArkConfig, words: String, datadir: String): ArkWallet =
        throw ArkError.Other("No Bark bridge is registered on iOS")
}
