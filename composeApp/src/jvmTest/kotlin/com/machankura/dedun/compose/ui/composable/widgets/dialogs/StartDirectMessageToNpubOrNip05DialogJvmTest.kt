package com.machankura.dedun.compose.ui.composable.widgets.dialogs

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.machankura.dedun.compose.ui.composable.navigation.routes.ImplementationPendingRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.ProfilePreviewRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.BennuTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the new-chat dialog does with what is pasted into it.
 *
 * The button and the route read one decoded key, so the case that used to close the
 * dialog and do nothing -- `nostr:npub1…`, which passed the check and failed the
 * decode -- is the row worth having. The nip05 row pins where that branch still
 * lands, so that it is recorded rather than assumed.
 */
@OptIn(ExperimentalTestApi::class)
class StartDirectMessageToNpubOrNip05DialogJvmTest {

    private val activeUserPublicKey = "a".repeat(64)
    private val npub = "npub1zutzeysacnf9rru6zqwmxd54mud0k44tst6l70ja5mhv8jjumytsd2x7nu"
    private val publicKey = "17162c921dc4d2518f9a101db33695df1afb56ab82f5ff3e5da6eec3ca5cd917"
    private val nsec = "nsec10allq0gjx7fddtzef0ax00mdps9t2kmtrldkyjfs8l5xruwvh2dq0lhhkp"

    @Test
    fun `a pasted npub opens the person's profile, and starts no chat`() {
        var opened: Route? = null

        render(onNavigateToRoute = { opened = it }) {
            onNodeWithText("Find profile").assertIsNotEnabled()
            onNodeWithText("Input npub... or nip05").performTextInput(npub)
            onNodeWithText("Find profile").assertIsEnabled().performClick()
        }

        assertEquals(ProfilePreviewRoute(activeUserPublicKey, profilePublicKey = publicKey), opened)
    }

    /** The row that failed before: accepted by the field's check, refused by the decode, and nothing happened. */
    @Test
    fun `a nostr-prefixed npub is the same person`() {
        var opened: Route? = null

        render(onNavigateToRoute = { opened = it }) {
            onNodeWithText("Input npub... or nip05").performTextInput("nostr:$npub")
            onNodeWithText("Find profile").assertIsEnabled().performClick()
        }

        assertEquals(ProfilePreviewRoute(activeUserPublicKey, profilePublicKey = publicKey), opened)
    }

    /** A field asking whom to write to must never take a secret for an answer. */
    @Test
    fun `an nsec, or an npub with a broken checksum, enables nothing`() {
        var opened: Route? = null

        render(onNavigateToRoute = { opened = it }) {
            onNodeWithText("Input npub... or nip05").performTextInput(nsec)
            onNodeWithText("Find profile").assertIsNotEnabled()
        }
        render(onNavigateToRoute = { opened = it }) {
            onNodeWithText("Input npub... or nip05").performTextInput(npub.dropLast(1) + "q")
            onNodeWithText("Find profile").assertIsNotEnabled()
        }

        assertNull(opened)
    }

    /** Recognised by shape alone, and still the placeholder: resolving one is out of scope. */
    @Test
    fun `a nip05 still lands on the placeholder`() {
        var opened: Route? = null

        render(onNavigateToRoute = { opened = it }) {
            onNodeWithText("Input npub... or nip05").performTextInput("alice@example.com")
            onNodeWithText("Find profile").assertIsEnabled().performClick()
        }

        assertEquals(ImplementationPendingRoute("Direct Message to alice@example.com"), opened)
    }

    private fun render(
        onNavigateToRoute: (Route) -> Unit,
        assertions: ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(width = 400, height = 800) {
        setContent {
            BennuTheme {
                ProvideSnackbarHost {
                    StartDirectMessageToNpubOrNip05Dialog(
                        activeUserPublicKey = activeUserPublicKey,
                        scope = rememberCoroutineScope(),
                        toggleOpenDialogSetting = {},
                        onNavigateToRoute = onNavigateToRoute,
                    )
                }
            }
        }

        assertions()
        // The navigation runs when the close-the-dialog job completes; give it the frame.
        waitForIdle()
    }
}
