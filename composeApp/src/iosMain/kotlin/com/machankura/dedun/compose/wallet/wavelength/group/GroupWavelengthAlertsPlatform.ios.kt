package com.machankura.dedun.compose.wallet.wavelength.group

import com.machankura.dedun.compose.PlatformContext

/**
 * Nothing, for now.
 *
 * A local notification on iOS needs `UNUserNotificationCenter` authorisation, which is a prompt the app
 * has never asked for and should ask for where a member can see what it is for -- not from a watcher.
 * The room line is still written. This is the one platform gap in B7, and it is a prompt's worth of
 * work rather than a design question.
 */
actual fun groupWavelengthAlerts(context: PlatformContext): GroupWavelengthAlerts = GroupWavelengthAlerts.None
