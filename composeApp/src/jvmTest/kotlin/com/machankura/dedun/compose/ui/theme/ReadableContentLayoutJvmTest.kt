package com.machankura.dedun.compose.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the theme does to a real composition in a window of a given width.
 *
 * [BreakpointTest] and [ReadableMeasureTest] assert arithmetic. This asserts the two
 * things arithmetic cannot reach, in a window that genuinely is 1400 pixels wide:
 *
 *  - that `currentBreakpoint()` reads the window at all. It goes through
 *    `currentWindowDpSize()`, `LocalWindowInfo` and `LocalDensity`, and a version of it
 *    that measured the wrong thing -- the parent's constraints, say -- would return
 *    `Compact` everywhere and pass every unit test in the suite.
 *  - that `readableContent()` caps *and* centres. It is `fillMaxWidth` then
 *    `wrapContentWidth` then `widthIn`, and every permutation of those three compiles and
 *    renders something plausible at phone width. Swap the last two and the content is
 *    centred but never capped; drop the first and it is capped but hugs the left edge.
 *
 * The test density is 1, so a pixel is a dp and the window widths below are the ones the
 * phase's acceptance criterion names.
 */
@OptIn(ExperimentalTestApi::class)
class ReadableContentLayoutJvmTest {

    /** 60 characters of 16sp `bodyLarge` at the default text scale. */
    private val measure = 480.dp

    @Test
    fun `a desktop window caps the content and centres it`() = inWindow(1400) {
        // (1400 - 480) / 2 each side. The window's extra width becomes margin, which is
        // what M3's single-pane canonical layout does -- not a 1400dp line of text, and
        // not a 480dp column pinned against one edge.
        onNodeWithTag(Content).assertWidthIsEqualTo(measure)
        onNodeWithTag(Content).assertLeftPositionInRootIsEqualTo(460.dp)
    }

    @Test
    fun `a phone window is left exactly as it was`() = inWindow(400) {
        // The claim that let this be applied to all 49 screen roots in one pass: below the
        // measure it is not a cap, an inset or a centring -- it is nothing. If this fails,
        // every phone layout in the app moved.
        onNodeWithTag(Content).assertWidthIsEqualTo(400.dp)
        onNodeWithTag(Content).assertLeftPositionInRootIsEqualTo(0.dp)
    }

    @Test
    fun `the measure itself is the last width left alone`() = inWindow(480) {
        onNodeWithTag(Content).assertWidthIsEqualTo(measure)
        onNodeWithTag(Content).assertLeftPositionInRootIsEqualTo(0.dp)
    }

    @Test
    fun `the theme classifies the window it is actually in`() {
        listOf(
            400 to Breakpoint.Compact,
            700 to Breakpoint.Medium,
            1000 to Breakpoint.Expanded,
            1400 to Breakpoint.Large,
            1800 to Breakpoint.ExtraLarge,
        ).forEach { (windowWidth, expected) ->
            var seen: Breakpoint? = null
            var margin: Dp? = null
            runDesktopComposeUiTest(width = windowWidth, height = 900) {
                setContent {
                    DedunTheme {
                        seen = MaterialTheme.breakpoint
                        margin = MaterialTheme.spacing.screenMargin
                    }
                }
            }

            assertEquals(expected, seen, "a ${windowWidth}dp window")
            assertEquals(
                if (expected == Breakpoint.Compact) 16.dp else 24.dp,
                margin,
                "the screen margin in a ${windowWidth}dp window",
            )
        }
    }

    /**
     * Composes a `readableContent()` column in a window [windowWidth] pixels across.
     *
     * The tagged node is a child of the modifier rather than the modifier's own node:
     * `fillMaxWidth` makes that node the full window, and it is the child inside the cap
     * whose width and position are the thing under test.
     */
    private fun inWindow(windowWidth: Int, assertions: androidx.compose.ui.test.ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = windowWidth, height = 900) {
            setContent { TaggedContent() }
            assertions()
        }

    @Composable
    private fun TaggedContent() {
        DedunTheme {
            Box(Modifier.readableContent()) {
                Box(Modifier.fillMaxWidth().height(8.dp).testTag(Content))
            }
        }
    }

    private companion object {
        const val Content = "content"
    }
}
