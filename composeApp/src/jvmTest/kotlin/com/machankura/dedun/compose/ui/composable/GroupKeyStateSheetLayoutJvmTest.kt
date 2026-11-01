package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlin.test.Test
import kotlin.time.Instant
import com.machankura.dedun.compose.database.model.GroupKeyState
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.BennuTheme

/**
 * The one thing about this sheet that reading it does not settle.
 *
 * Everything in it is a `Text` in a `Column`, which needs no test -- except that
 * what those texts hold is hex and JSON: a 64-character key, a 128-character
 * signature, and an event serialised as one line with almost no spaces in it.
 * Compose wraps at word boundaries, and a "word" here is longer than any phone
 * is wide. If it does not break inside a token, the sheet renders a line running
 * off the side of the screen with the signature unreadable and the copy button
 * the only way to get at it.
 *
 * So this measures a real composition at phone width and asserts the content
 * stayed inside it. It is also why the sheet's body is a composable of its own:
 * a bottom sheet is a popup in its own window, which a layout test cannot reach.
 */
@OptIn(ExperimentalTestApi::class)
class GroupKeyStateSheetLayoutJvmTest {

    private val thresholdPublicKey = "02" + "a1b2c3d4".repeat(8)

    private val adminRoomId = "d4c3b2a1".repeat(8)

    private val keyState = GroupKeyState(
        chatRoomId = adminRoomId,
        dkgSessionId = "c".repeat(64),
        thresholdPublicKey = thresholdPublicKey,
        derivationPath = "m/9420/0/0",
        announcedBy = adminRoomId,
        announcedAt = Instant.fromEpochSeconds(1_700_000_000)
    )

    private val signedEvent = GroupSignedEvent.fromEvent(
        event = Event(
            id = "e".repeat(64),
            pubKey = adminRoomId,
            createdAt = 1_700_000_000,
            kind = GroupKeyStateEvent.KIND,
            tags = GroupKeyStateEvent.assembleTags(
                chatRoomId = adminRoomId,
                dkgSessionId = "c".repeat(64)
            ),
            content = thresholdPublicKey,
            sig = "f".repeat(128)
        ),
        chatRoomId = adminRoomId,
        derivationPath = "m/9420/0/0",
    )

    /** What the sheet is asked to fit into, and what it has to wrap inside. */
    private val phoneWidth = 360.dp

    /** Exactly what the sheet shows and the copy button copies. */
    private val json = signedEvent.toEvent().toJson()

    @Test
    fun `the signed event wraps inside the sheet rather than running off it`() =
        runDesktopComposeUiTest(width = 800, height = 2400) {
            setContent {
                BennuTheme {
                    // The sheet reports a copy through the snackbar host, so it
                    // needs one in scope the way every screen does.
                    ProvideSnackbarHost {
                        Box(modifier = Modifier.width(phoneWidth).testTag(SHEET)) {
                            GroupKeyStateSheetContent(
                                groupKeyState = keyState,
                                signedEvent = signedEvent
                            )
                        }
                    }
                }
            }

            // The measurement that means something. A parent this narrow caps
            // the text's *layout* width whether it wraps or not, so width says
            // nothing -- height is what separates eight wrapped lines from one
            // clipped one. The JSON is over 400 characters at roughly 50 to a
            // line, so anything under four lines means it did not break.
            onNodeWithText(json).assertHeightIsAtLeast(60.dp)
            onNodeWithTag(SHEET).assertHeightIsAtLeast(200.dp)
        }

    @Test
    fun `a device holding no signed event still renders the state`() =
        runDesktopComposeUiTest(width = 800, height = 2400) {
            setContent {
                BennuTheme {
                    ProvideSnackbarHost {
                        Box(modifier = Modifier.width(phoneWidth).testTag(SHEET)) {
                            GroupKeyStateSheetContent(
                                groupKeyState = keyState,
                                signedEvent = null
                            )
                        }
                    }
                }
            }

            onNodeWithTag(SHEET).assertHeightIsAtLeast(200.dp)
        }

    private companion object {
        const val SHEET = "group-key-state-sheet"
    }
}
