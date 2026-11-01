package com.machankura.dedun.compose.wallet.ark

/** bark-android ships every ABI the app does: arm64-v8a, armeabi-v7a, x86 and x86_64. */
actual object ArkPlatform {
    actual val supported: Boolean = true

    actual suspend fun open(config: ArkConfig, words: String, datadir: String): ArkWallet =
        openBarkWallet(config, words, datadir)
}
