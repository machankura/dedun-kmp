package com.machankura.dedun.compose.ui.composable.widgets

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.machankura.dedun.compose.ui.theme.DedunTheme
import kotlin.test.Test

/** Stand-ins for a screen's UI state: two classes, and data that changes within one. */
private sealed interface Fixture {
    data object Loading : Fixture
    data class Loaded(val text: String) : Fixture
}

/**
 * That a screen's state change is a transition rather than a cut, and that ordinary data
 * changes are not.
 *
 * Both halves matter and the second is the one that is easy to get wrong. `AnimatedContent`
 * keyed on the state *value* would re-run the whole fade every time a loaded list grew by
 * one row -- a screen that flickers whenever a message arrives -- and it would look
 * entirely correct in any screenshot. Keying on the state's class is what separates "the
 * screen is now answering a different question" from "the answer got longer".
 *
 * The clock is held still so a frame in the middle of a transition can be inspected. That
 * frame is the whole test: during a crossfade both states are composed, and during a cut
 * only ever one is.
 */
@OptIn(ExperimentalTestApi::class)
class ScreenStateTransitionJvmTest {

    @Test
    fun `both states are on screen while one replaces the other`() =
        runDesktopComposeUiTest(400, 600) {
            var state by mutableStateOf<Fixture>(Fixture.Loading)
            mainClock.autoAdvance = false
            setContent { DedunTheme { ScreenStateTransition(state) { Render(it) } } }

            mainClock.advanceTimeBy(16)
            onNodeWithText("loading").assertIsDisplayed()

            runOnUiThread { state = Fixture.Loaded("first") }
            mainClock.advanceTimeBy(48)

            // The frame that distinguishes a transition from a cut.
            onNodeWithText("loading").assertExists()
            onNodeWithText("first").assertExists()

            mainClock.autoAdvance = true
            waitForIdle()
            onNodeWithText("loading").assertDoesNotExist()
            onNodeWithText("first").assertIsDisplayed()
        }

    @Test
    fun `data changing inside one state does not animate`() = runDesktopComposeUiTest(400, 600) {
        var state by mutableStateOf<Fixture>(Fixture.Loaded("first"))
        mainClock.autoAdvance = false
        setContent { DedunTheme { ScreenStateTransition(state) { Render(it) } } }

        mainClock.advanceTimeBy(16)
        onNodeWithText("first").assertIsDisplayed()

        runOnUiThread { state = Fixture.Loaded("second") }
        mainClock.advanceTimeBy(48)

        // Same class, so `contentKey` is unchanged and nothing crossfades: the old text is
        // gone at once rather than lingering through a fade nobody asked for.
        onNodeWithText("first").assertDoesNotExist()
        onNodeWithText("second").assertIsDisplayed()

        mainClock.autoAdvance = true
        waitForIdle()
    }

    @androidx.compose.runtime.Composable
    private fun Render(state: Fixture) = when (state) {
        Fixture.Loading -> Text("loading")
        is Fixture.Loaded -> Text(state.text)
    }
}
