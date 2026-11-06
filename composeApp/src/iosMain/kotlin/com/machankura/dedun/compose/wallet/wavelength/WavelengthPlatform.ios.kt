package com.machankura.dedun.compose.wallet.wavelength

import engineering.lightning.wavelength.EmbeddedWavelengthClient
import fr.acinq.phoenix.utils.PlatformContext

/** Not until the SDK's iOS has been compiled on a Mac: Phase 12 (D4). */
actual object WavelengthPlatform {
    actual val client: EmbeddedWavelengthClient? get() = null

    actual fun dataRoot(context: PlatformContext): String? = null

    actual fun scheduleRenewal(context: PlatformContext) = Unit
}
