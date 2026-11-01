package com.machankura.dedun.compose.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.UIKit.UIAccessibilityDarkerSystemColorsEnabled
import platform.UIKit.UIAccessibilityDarkerSystemColorsStatusDidChangeNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue

/**
 * iOS Settings > Accessibility > Display & Text Size > Increase Contrast.
 *
 * A boolean, not a three-step slider, so this answers [ThemeContrast.High] or
 * [ThemeContrast.Standard] and never [ThemeContrast.Medium]. Apple's own contrast work is
 * mostly done inside its system colours rather than exposed as a level; the one bit it
 * does expose is `UIAccessibilityDarkerSystemColorsEnabled`.
 *
 * **Not compiled on this machine.** The ios targets are declared only on macos (see
 * docs/jvm-target.md for why the composite build forces that), so this file has been
 * written against the UIKit and Foundation bindings rather than checked by a compiler.
 * The Android and jvm actuals of the same two functions are compiled and tested.
 */
@Composable
actual fun platformThemeContrast(): ThemeContrast {
    var contrast by remember { mutableStateOf(currentContrast()) }

    // The switch can be flipped while the app is foregrounded, and iOS announces it
    // rather than restarting anything -- so without this observer the new setting would
    // wait for the next cold start.
    DisposableEffect(Unit) {
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIAccessibilityDarkerSystemColorsStatusDidChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> contrast = currentContrast() }

        onDispose { NSNotificationCenter.defaultCenter.removeObserver(observer) }
    }

    return contrast
}

private fun currentContrast(): ThemeContrast =
    if (UIAccessibilityDarkerSystemColorsEnabled()) ThemeContrast.High else ThemeContrast.Standard

/**
 * Always `null`: dynamic colour means Material You, an android wallpaper-derived palette
 * with no iOS counterpart.
 */
@Composable
actual fun dynamicColorScheme(darkTheme: Boolean, dynamicColor: Boolean): ColorScheme? = null
