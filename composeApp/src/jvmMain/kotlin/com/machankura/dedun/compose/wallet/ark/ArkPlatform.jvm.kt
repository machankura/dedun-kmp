package com.machankura.dedun.compose.wallet.ark

/**
 * bark-jvm carries one native library, for Linux x86-64 with glibc 2.34 or later. On
 * macOS and Windows the profile's wallet row says the wallet is not available on this
 * device, until Phase 11 builds theirs. Checked from the system properties, so JNA never
 * tries to load a library that is not there.
 */
actual object ArkPlatform {
    actual val supported: Boolean = run {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        "linux" in os && (arch == "amd64" || arch == "x86_64")
    }

    actual suspend fun open(config: ArkConfig, words: String, datadir: String): ArkWallet {
        if (!supported) throw ArkError.Other("No Bark library for ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
        return openBarkWallet(config, words, datadir)
    }
}
