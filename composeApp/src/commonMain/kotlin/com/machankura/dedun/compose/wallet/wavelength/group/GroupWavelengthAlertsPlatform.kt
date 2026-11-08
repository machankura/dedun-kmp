package com.machankura.dedun.compose.wallet.wavelength.group

import com.machankura.dedun.compose.PlatformContext

/**
 * The platform's way of interrupting a member about a group's wallet, or nothing where it has none.
 *
 * B7 asks for **one channel**, separate from the foreground service's. `GroupWavelengthService`'s
 * channel says *this device is running the wallet* and is deliberately `IMPORTANCE_LOW`; merging the
 * two would silence the second with the first, which is the whole reason this is its own.
 */
expect fun groupWavelengthAlerts(context: PlatformContext): GroupWavelengthAlerts
