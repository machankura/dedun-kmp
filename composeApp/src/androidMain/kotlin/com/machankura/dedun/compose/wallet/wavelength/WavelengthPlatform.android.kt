package com.machankura.dedun.compose.wallet.wavelength

import engineering.lightning.wavelength.EmbeddedWavelengthClient
import engineering.lightning.wavelength.embedded.Wavelength
import fr.acinq.phoenix.utils.PlatformContext

actual object WavelengthPlatform {
    actual val client: EmbeddedWavelengthClient? get() = Wavelength.client

    // Not Wavelength.defaultDataDir, which is filesDir and so inside allowBackup="true": a wallet
    // database restored behind the operator's back is worse than none (D8).
    actual fun dataRoot(context: PlatformContext): String? = context.applicationContext.noBackupFilesDir.absolutePath

    actual fun scheduleRenewal(context: PlatformContext) = WavelengthMaintenanceWorker.schedule(context.applicationContext)
}
