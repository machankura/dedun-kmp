package com.machankura.dedun.compose.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertTrue

/** Two destinations, so a transition has something to run between. */
@Serializable
object First

@Serializable
object Second

/**
 * That the reduced-motion setting reaches the navigation transitions, measured mid-flight.
 *
 * The claim cannot be read off the source and cannot be asserted on the values:
 * `EnterTransition` has no public shape to inspect, so "this one slides and that one does
 * not" is only answerable by looking at where the arriving screen *is* part-way through.
 *
 * The clock is held still and advanced by hand. At a third of the way in, a sliding screen
 * is still some distance from its resting place and a fading one is already at it.
 */
@OptIn(ExperimentalTestApi::class)
class NavigationMotionJvmTest {

    private val window = 800

    @Test
    fun `the arriving screen slides when motion is not reduced`() {
        assertTrue(
            arrivingOffsetMidTransition(reducedMotion = false) > 1.dp.value,
            "the second screen was already in place, so nothing slid",
        )
    }

    @Test
    fun `it arrives in place when the platform asks for reduced motion`() {
        // Not "no transition": the screen still fades, which M3 and WCAG 2.3.3 both allow.
        // What goes is the movement.
        val offset = arrivingOffsetMidTransition(reducedMotion = true)
        assertTrue(
            offset <= 1.dp.value,
            "the second screen was $offset dp from its resting place, so it still slid",
        )
    }

    /**
     * How far the arriving screen's leading edge is from zero, a third of the way through.
     *
     * A third rather than a frame or two: the scheme's spatial spec is a spring, and a
     * spring's first frames are slow enough that a fade and a slide are hard to tell apart
     * there.
     */
    private fun arrivingOffsetMidTransition(reducedMotion: Boolean): Float {
        var offset = 0f
        runDesktopComposeUiTest(width = window, height = 600) {
            lateinit var controller: NavHostController
            mainClock.autoAdvance = false

            setContent {
                controller = rememberNavController()
                // Through the theme's parameter rather than by providing the local
                // around it. Providing it outside is what this test did first, and
                // `BennuTheme` silently overwrote it -- the reduced case read 54dp of
                // slide. That is also why `reducedMotion` is a `BennuTheme` parameter
                // rather than something the theme only reads from the platform: the
                // desktop actual is a hardcoded `false`, and a value nothing can override
                // is a value nothing can test either.
                BennuTheme(reducedMotion = reducedMotion) {
                    NavHost(
                        navController = controller,
                        startDestination = First,
                        enterTransition = NavigationMotion.enter(),
                        exitTransition = NavigationMotion.exit(),
                        popEnterTransition = NavigationMotion.popEnter(),
                        popExitTransition = NavigationMotion.popExit(),
                    ) {
                        composable<First> { Box(Modifier.fillMaxSize().testTag("first")) }
                        composable<Second> { Box(Modifier.fillMaxSize().testTag("second")) }
                    }
                }
            }

            mainClock.advanceTimeBy(16)
            // On the main thread, and not through `runOnIdle`: navigation-compose asserts
            // the thread, and nothing is idle while the clock is held.
            runOnUiThread { controller.navigate(Second) }
            mainClock.advanceTimeBy(16)

            // A third of the scheme's default spatial duration. Long enough that a spring
            // has visibly moved, short enough that it has not settled.
            mainClock.advanceTimeBy(120)

            offset = onNodeWithTag("second").getUnclippedBoundsInRoot().left.value

            // Let the transition finish before the composition is torn down. A back stack
            // entry caught mid-transition is still below CREATED, and navigation-compose
            // throws trying to move it to DESTROYED.
            mainClock.autoAdvance = true
            waitForIdle()
        }
        return offset
    }
}
