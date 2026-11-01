package com.machankura.dedun.compose.wallet.ark

/**
 * Bark has no Kotlin/Native binding (Phase 0, spike 6: Gobley's uniffi is 0.29, Bark's
 * 0.31), so iOS reaches its Swift package through the [ArkEngine] the iOS app registers
 * -- `docs/bark-wallet.md`, Phase 8. No engine registered means not on this device.
 */
actual object ArkPlatform {
    actual val supported: Boolean
        get() = ArkEngineRegistry.engine != null

    actual suspend fun open(config: ArkConfig, words: String, datadir: String): ArkWallet {
        val engine = ArkEngineRegistry.engine ?: throw ArkError.Other("No Bark bridge is registered on iOS")
        return openEngineWallet(engine, config, words, datadir)
    }
}
