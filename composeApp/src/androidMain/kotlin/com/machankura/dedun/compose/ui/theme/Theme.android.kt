package com.machankura.dedun.compose.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Android 14 (API 34) added a three-step contrast setting under Accessibility > Display.
 * `UiModeManager.getContrast()` reports it as a float, and the platform documents the
 * three positions as 0.0, 0.5 and 1.0. Values between are treated as the nearer step
 * rather than rejected -- the API returns a float, so a future finer-grained slider
 * should degrade to the closest scheme this app has rather than to Standard.
 *
 * Below API 34 there is nothing to read and the answer is [ThemeContrast.Standard]. The
 * app's minSdk is 26.
 */
@Composable
actual fun platformThemeContrast(): ThemeContrast {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return ThemeContrast.Standard

    val context = LocalContext.current
    val uiModeManager = remember(context) {
        context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    } ?: return ThemeContrast.Standard

    var contrast by remember(uiModeManager) {
        mutableStateOf(contrastStepFor(uiModeManager.contrast))
    }

    // The setting can be changed while the app is in the foreground, and unlike a theme
    // or locale change it does not restart the activity or arrive as a Configuration
    // update -- so without this listener the new value would only take effect on the
    // next cold start, which is the case the setting exists for.
    DisposableEffect(uiModeManager, context) {
        val listener = UiModeManager.ContrastChangeListener { value ->
            contrast = contrastStepFor(value)
        }
        // context.mainExecutor rather than ContextCompat.getMainExecutor: it needs API 28
        // and this whole branch is already gated on 34, and it keeps androidx.core off
        // this file's imports -- composeApp does not declare it, it only arrives
        // transitively through activity-compose.
        uiModeManager.addContrastChangeListener(context.mainExecutor, listener)
        onDispose { uiModeManager.removeContrastChangeListener(listener) }
    }

    return contrast
}

/** Nearest of the platform's three documented positions. */
private fun contrastStepFor(value: Float): ThemeContrast = when {
    value < 0.25f -> ThemeContrast.Standard
    value < 0.75f -> ThemeContrast.Medium
    else -> ThemeContrast.High
}

/**
 * Material You, from Android 12 (API 31).
 *
 * No contrast argument is needed. From API 34 the `android.R.color.system_*` resources
 * these are built from shift with the contrast setting themselves, so a dynamic scheme
 * already carries it; passing this app's own contrast on top would apply it twice.
 */
@Composable
actual fun dynamicColorScheme(darkTheme: Boolean, dynamicColor: Boolean): ColorScheme? {
    if (!dynamicColor || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null

    val context = LocalContext.current
    return if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}
