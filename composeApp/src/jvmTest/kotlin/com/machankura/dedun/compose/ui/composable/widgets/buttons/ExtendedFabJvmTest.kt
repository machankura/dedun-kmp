package com.machankura.dedun.compose.ui.composable.widgets.buttons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.machankura.dedun.compose.ui.composable.widgets.Decorative
import com.machankura.dedun.compose.ui.theme.BennuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two things the shared FAB decides for every screen's primary action.
 *
 * **The label is what the button announces.** M3's `text`/`icon` overload clears the
 * label from the merged semantics tree, so a screen reader hears only the icon and a
 * test cannot find the button by what it says. Every screen test in the app that
 * reaches a FAB by `onNodeWithText` depends on this one staying the other way, and
 * a FAB with a decorative icon has no name at all without it.
 *
 * **Unavailable is a refusal, not only a look.** M3 gives a FAB no `enabled`, so the
 * widget has to both mark the node disabled and drop the press; a button that only
 * looked disabled would be announced, and pressed, as one that works.
 */
@OptIn(ExperimentalTestApi::class)
class ExtendedFabJvmTest {

    @Test
    fun `the button is found by its label in the merged tree, with the icon beside it`() =
        runDesktopComposeUiTest(400, 200) {
            setContent {
                BennuTheme {
                    ExtendedFab(
                        label = "Propose post",
                        icon = { Icon(Icons.Default.Add, contentDescription = "Propose post icon") },
                        onClick = {},
                    )
                }
            }

            // No `useUnmergedTree`: this is the tree a screen reader is given.
            onNodeWithText("Propose post").assertIsDisplayed().assertIsEnabled()

            // The icon first, then a gap, then the label -- not one touching the other.
            val icon = onNodeWithContentDescription("Propose post icon", useUnmergedTree = true)
                .getBoundsInRoot()
            val label = onNodeWithText("Propose post", useUnmergedTree = true).getBoundsInRoot()
            assertTrue(icon.right < label.left, "the label is not after the icon")
        }

    @Test
    fun `a decorative icon leaves the label as the button's only name, which is enough`() =
        runDesktopComposeUiTest(400, 200) {
            setContent {
                BennuTheme {
                    ExtendedFab(
                        label = "Broadcast all",
                        icon = { Icon(Icons.Default.Add, contentDescription = Decorative) },
                        onClick = {},
                    )
                }
            }

            onNodeWithText("Broadcast all").assertIsDisplayed()
        }

    @Test
    fun `disabled is announced as disabled and drops the press, and enabled takes it`() {
        var presses = 0

        runDesktopComposeUiTest(400, 200) {
            setContent {
                BennuTheme {
                    ExtendedFab(
                        label = "Propose post",
                        icon = { Icon(Icons.Default.Add, contentDescription = Decorative) },
                        enabled = false,
                        onClick = { presses++ },
                    )
                }
            }

            onNodeWithText("Propose post").assertIsNotEnabled().performClick()
        }
        assertEquals(0, presses)

        runDesktopComposeUiTest(400, 200) {
            setContent {
                BennuTheme {
                    ExtendedFab(
                        label = "Propose post",
                        icon = { Icon(Icons.Default.Add, contentDescription = Decorative) },
                        onClick = { presses++ },
                    )
                }
            }

            onNodeWithText("Propose post").assertIsEnabled().performClick()
        }
        assertEquals(1, presses)
    }
}
