package com.machankura.dedun.compose.wallet.wavelength

import engineering.lightning.wavelength.EmbeddedWavelengthClient
import fr.acinq.phoenix.utils.PlatformContext

/**
 * What this platform has of the daemon -- `docs/wavelength-wallet.md`, D3 and D4. Everything
 * else about the wallet is common code: the client is already a Kotlin interface here, so
 * there is no port to write and no adapter behind one.
 */
expect object WavelengthPlatform {
    /** The process's one client, or null where upstream ships no runtime. */
    val client: EmbeddedWavelengthClient?

    /** Where wallets live, somewhere no backup reaches (D8). Null with no runtime. */
    fun dataRoot(context: PlatformContext): String?
}
