package com.machankura.dedun.compose.ui.theme

import android.database.ContentObserver
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Android has no "reduce motion" switch. What it has is **Remove animations**, under
 * Accessibility, and what that does is set the three animation duration scales to zero --
 * so the honest reading is `ANIMATOR_DURATION_SCALE == 0`.
 *
 * The platform already applies that scale to `ValueAnimator`, but **not to Compose**:
 * Compose animations run on its own clock and ignore it entirely. So an app that draws its
 * own transitions has to read the setting itself, which is what this is for.
 *
 * `TRANSITION_ANIMATION_SCALE` and `WINDOW_ANIMATION_SCALE` are the other two the switch
 * sets. Reading one of the three is enough: the accessibility toggle writes all three
 * together, and a developer-options user who has set only one has made a deliberate choice
 * about a different thing.
 */
@Composable
actual fun platformReducedMotion(): Boolean {
    val context = LocalContext.current
    val resolver = remember(context) { context.contentResolver } ?: return false

    var reduced by remember(resolver) { mutableStateOf(animationsRemoved(resolver)) }

    // The setting is changed from the Accessibility screen, which means leaving the app and
    // coming back -- but a split screen, a tablet with two apps, or a quick settings tile
    // all change it without the app going away. An observer costs one registration and
    // removes the whole class of "it only took effect after a restart".
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                reduced = animationsRemoved(resolver)
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }

    return reduced
}

private fun animationsRemoved(resolver: android.content.ContentResolver): Boolean =
    runCatching {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)
