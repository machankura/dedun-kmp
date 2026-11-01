package com.machankura.dedun.compose.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.awt.Toolkit

/**
 * Desktop has no portable contrast setting, and this reads the one platform that exposes
 * a usable signal through AWT.
 *
 * Windows publishes its high contrast mode as the `win.highContrast.on` desktop property,
 * which AWT surfaces on that platform and reports as `null` everywhere else. There is no
 * medium step -- Windows high contrast is a boolean -- so this answers
 * [ThemeContrast.High] or [ThemeContrast.Standard] and never [ThemeContrast.Medium].
 *
 * On linux and macos the answer is always [ThemeContrast.Standard]. macos does have
 * "Increase contrast" and linux desktops have their own equivalents, but neither reaches
 * AWT, and reading them means a native call per platform. **Until the app has a settings
 * screen this is simply unhonoured there**, which is worth knowing rather than papering
 * over: the setting should ultimately be a preference the user can override anyway,
 * since a desktop app cannot always see what the desktop was told.
 */
@Composable
actual fun platformThemeContrast(): ThemeContrast {
    val toolkit = remember { runCatching { Toolkit.getDefaultToolkit() }.getOrNull() }
        ?: return ThemeContrast.Standard

    var contrast by remember(toolkit) { mutableStateOf(windowsHighContrast(toolkit)) }

    // Windows fires a property change when the user toggles high contrast, so the app
    // does not need restarting. The listener is a no-op on platforms that never publish
    // the property.
    DisposableEffect(toolkit) {
        val listener = java.beans.PropertyChangeListener {
            contrast = windowsHighContrast(toolkit)
        }
        toolkit.addPropertyChangeListener(HIGH_CONTRAST_PROPERTY, listener)
        onDispose { toolkit.removePropertyChangeListener(HIGH_CONTRAST_PROPERTY, listener) }
    }

    return contrast
}

private const val HIGH_CONTRAST_PROPERTY = "win.highContrast.on"

private fun windowsHighContrast(toolkit: Toolkit): ThemeContrast =
    if (toolkit.getDesktopProperty(HIGH_CONTRAST_PROPERTY) == true) {
        ThemeContrast.High
    } else {
        ThemeContrast.Standard
    }

/**
 * Always `null`: dynamic colour means Material You, which reads a wallpaper-derived
 * palette from the android system and has no desktop counterpart. The app's own schemes
 * answer whatever the caller asks for.
 */
@Composable
actual fun dynamicColorScheme(darkTheme: Boolean, dynamicColor: Boolean): ColorScheme? = null
