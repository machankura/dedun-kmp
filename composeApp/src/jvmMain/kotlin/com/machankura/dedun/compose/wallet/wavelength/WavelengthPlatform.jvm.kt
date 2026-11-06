package com.machankura.dedun.compose.wallet.wavelength

import engineering.lightning.wavelength.EmbeddedWavelengthClient
import fr.acinq.phoenix.utils.PlatformContext

/** Upstream has no desktop runtime: *Not available on this device* (D4). */
actual object WavelengthPlatform {
    actual val client: EmbeddedWavelengthClient? get() = null

    actual fun dataRoot(context: PlatformContext): String? = null

    actual fun scheduleRenewal(context: PlatformContext) = Unit
}
