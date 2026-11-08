package com.machankura.dedun.compose.ui.composable.widgets

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * The window's own brightness override, restored on the way out.
 *
 * `screenBrightness` on the window's attributes is a per-window override rather than the device
 * setting, so `BRIGHTNESS_OVERRIDE_NONE` hands it back to whatever the member had -- automatic
 * brightness included. Nothing here is remembered or written down.
 *
 * A no-op where the composable is not hosted in an `Activity`, which is every preview and every test.
 */
@Composable
actual fun RaiseBrightnessWhileVisible() {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(activity) {
        val window = activity.window
        val previous = window.attributes.screenBrightness
        window.attributes = window.attributes.apply { screenBrightness = 1f }
        onDispose {
            window.attributes = window.attributes.apply {
                screenBrightness = previous.takeUnless { it == 1f } ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }
}
