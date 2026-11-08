package com.machankura.dedun.compose.wallet.wavelength.group

import com.machankura.dedun.compose.PlatformContext

/**
 * Nothing, for now.
 *
 * The desktop target has no notification surface this app has built, and a dialog raised from a
 * background watcher would interrupt whatever the member was doing with no way to have asked for it.
 * The room line is still written, which is where the desktop app shows it.
 */
actual fun groupWavelengthAlerts(context: PlatformContext): GroupWavelengthAlerts = GroupWavelengthAlerts.None
