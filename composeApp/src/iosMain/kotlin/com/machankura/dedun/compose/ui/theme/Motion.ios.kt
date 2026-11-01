package com.machankura.dedun.compose.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIAccessibilityReduceMotionStatusDidChangeNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue

/**
 * iOS names the setting exactly: Settings > Accessibility > Motion > Reduce Motion.
 *
 * The one platform of the three where the answer is a single documented call. The
 * notification is the same shape as the darker-system-colours one
 * [platformThemeContrast] observes on this platform, and for the same reason: it can be
 * turned on from Control Center without the app being backgrounded.
 */
@Composable
actual fun platformReducedMotion(): Boolean {
    var reduced by remember { mutableStateOf(UIAccessibilityIsReduceMotionEnabled()) }

    DisposableEffect(Unit) {
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIAccessibilityReduceMotionStatusDidChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            reduced = UIAccessibilityIsReduceMotionEnabled()
        }
        onDispose { NSNotificationCenter.defaultCenter.removeObserver(observer) }
    }

    return reduced
}
