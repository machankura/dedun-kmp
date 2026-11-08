package com.machankura.dedun.compose.ui.composable

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.composable.widgets.wallet.GroupWavelengthGlossaryButton
import com.machankura.dedun.compose.ui.composable.widgets.wallet.GroupWavelengthGlossarySheet
import com.machankura.dedun.compose.ui.theme.DedunTheme
import kotlin.test.Test

/**
 * The glossary explains three terms, is reachable, and costs nothing until it is asked for.
 *
 * Dismissal is `ModalBottomSheet`'s own -- the scrim, a drag, the back press -- and none of those
 * is a semantic node a test can address, so what is asserted here is the part this file owns: that
 * the sheet is absent until the button is pressed, that pressing it explains all three terms, and
 * that the button has a name to press. A glossary whose icon reads as "button" is an explanation
 * nobody blind can reach, which is why the description is asserted rather than assumed.
 */
@OptIn(ExperimentalTestApi::class)
class GroupWavelengthGlossaryJvmTest {
    private val phone = 400

    @Test
    fun `the three terms are each explained once`() = runDesktopComposeUiTest(phone, 1600) {
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthGlossarySheet {} } } }
        onNodeWithText("A round").assertIsDisplayed()
        onNodeWithText("The Ark operator, and whoever runs the wallet").assertIsDisplayed()
        onNodeWithText("Leaving on your own").assertIsDisplayed()
        onNodeWithText("A round is the moment the group's coins are signed over to fresh ones", substring = true).assertIsDisplayed()
        onNodeWithText("The Ark operator is the server the group's coins live with.", substring = true).assertIsDisplayed()
        onNodeWithText("your device publishes the coins to the chain by itself", substring = true).assertIsDisplayed()
        // Once each: a second copy of a term would mean two glossaries disagreeing, which is the
        // thing one shared sheet exists to prevent.
        onAllNodesWithText("A round").assertCountEquals(1)
    }

    @Test
    fun `nothing of the glossary is on the screen until the button is pressed`() = runDesktopComposeUiTest(phone, 1600) {
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthGlossaryButton() } } }
        onAllNodesWithText("A round").assertCountEquals(0)
        onNodeWithContentDescription("What these words mean").performClick()
        onNodeWithText("A round").assertIsDisplayed()
        onNodeWithText("Leaving on your own").assertIsDisplayed()
    }

    @Test
    fun `the button says what it opens`() = runDesktopComposeUiTest(phone, 1600) {
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthGlossaryButton() } } }
        onNodeWithContentDescription("What these words mean").assertIsDisplayed()
    }
}
