package com.machankura.dedun.compose.ui.composable.widgets

import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runAndroidComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.activity.ComponentActivity
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * The one thing in the group Wavelength redesign a desktop test cannot reach: a real window's
 * brightness.
 *
 * `RaiseBrightnessWhileVisible` is twenty lines and shipped unverified in phase 4, because this source
 * set could not host a composable -- its two other tests are workers and need no composition. Phase 9
 * added `compose.uiTest` here for exactly this, with `ui-test-manifest`'s empty `ComponentActivity` to
 * host the content in -- `MainActivity` calls `setContent` itself, so a test that calls it again gets
 * "has already set content".
 *
 * What is asserted is the contract rather than the effect: the window carries the override while the
 * composable is on screen and `BRIGHTNESS_OVERRIDE_NONE` after it leaves -- which is what hands the
 * setting back to whatever the member had, automatic brightness included.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class BrightnessDeviceTest {

    @Test
    fun theWindowIsTurnedUpWhileItIsOnScreenAndHandedBackAfter() = runAndroidComposeUiTest<ComponentActivity> {
        var showing by mutableStateOf(false)
        setContent { if (showing) RaiseBrightnessWhileVisible() }
        waitForIdle()

        val window = requireNotNull(activity).window
        assertEquals(
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
            window.attributes.screenBrightness,
            "nothing is overridden before the screen that wants it",
        )

        showing = true
        waitForIdle()
        assertEquals(1f, window.attributes.screenBrightness, "turned up while a code is being held out")

        showing = false
        waitForIdle()
        assertEquals(
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE,
            window.attributes.screenBrightness,
            "and handed back, rather than left bright",
        )
    }
}
