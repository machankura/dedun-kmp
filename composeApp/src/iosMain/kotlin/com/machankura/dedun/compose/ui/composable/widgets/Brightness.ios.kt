package com.machankura.dedun.compose.ui.composable.widgets

import androidx.compose.runtime.Composable

/**
 * Deliberately nothing.
 *
 * `UIScreen.mainScreen.brightness` is the device's setting rather than a per-window override: raising
 * it turns the phone's brightness up and leaves it up, and restoring it on the way out would fight
 * whatever the member or automatic brightness did meanwhile. A dim QR code is the lesser harm.
 */
@Composable
actual fun RaiseBrightnessWhileVisible() = Unit
