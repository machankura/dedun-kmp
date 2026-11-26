package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.view.state.ProposeGroupEventUIState
import kotlin.test.Test

/**
 * What the paste screen says back as an event is typed into it.
 *
 * The screen's one job beyond a text field is to tell a member, before a quorum
 * is asked, what the group would be signing or why it will not be asked. Both
 * are read live off the paste, so both are tested by typing: a profile reads back
 * as the signing screen would describe it, junk is named as junk, and a kind
 * with no section to land in is refused with the kinds that have one.
 */
@OptIn(ExperimentalTestApi::class)
class ProposeGroupEventScreenJvmTest {

    private val chatRoomId = "d4c3b2a1".repeat(8)

    private val profile = """{"kind":0,"created_at":1735689600,"tags":[],""" +
        """"content":"{\"name\":\"Translation collective\",\"about\":\"Hard books\"}"}"""

    @Test
    fun `a pasted profile is read back as the signing screen would say it`() = render(state()) {
        onNodeWithText("Unsigned event").performTextInput(profile)

        onNodeWithText("What the group would sign").assertIsDisplayed()
        onNodeWithText("Kind 0 · Nostr profile").assertIsDisplayed()
        onNodeWithText("Translation collective · Hard books").assertIsDisplayed()
    }

    @Test
    fun `a pasted post is read back by its words`() = render(state()) {
        onNodeWithText("Unsigned event").performTextInput("""{"kind": 1, "content": "We meet on Thursday"}""")

        onNodeWithText("Kind 1 · Post").assertIsDisplayed()
        onNodeWithText("We meet on Thursday").assertIsDisplayed()
    }

    @Test
    fun `junk is named as junk`() =
        render(state()) {
            onNodeWithText("Unsigned event").performTextInput("this is not an event")
            onNodeWithText("That isn't JSON.").assertIsDisplayed()
            onNodeWithText("What the group would sign").assertDoesNotExist()
        }

    @Test
    fun `a kind the screen cannot file is refused with the kinds it can`() = render(state()) {
        onNodeWithText("Unsigned event").performTextInput("""{"kind": 30023, "content": "An article"}""")

        onNodeWithText("Kind 30023 has nowhere to go on the group's screen.", substring = true)
            .assertIsDisplayed()
        onNodeWithText("It can file kinds 0, 1.", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a relay list is refused, since the group's relay lists did not come back`() = render(state()) {
        onNodeWithText("Unsigned event").performTextInput("""{"kind": 10002, "tags": []}""")

        onNodeWithText("Kind 10002 has nowhere to go on the group's screen.", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `a member holding no share of the key is told so and the button is inert`() = render(
        state(canSign = false)
    ) {
        onNodeWithText("This group has no shared key, so it cannot sign an event.", substring = true)
            .assertIsDisplayed()
        onNodeWithContentDescription("Propose event").assertIsNotEnabled()
    }

    private fun state(canSign: Boolean = true) = ProposeGroupEventUIState.Loaded(
        localChatRoom = LocalChatRoom(
            chatRoom = ChatRoom(
                id = chatRoomId,
                userPublicKey = "a".repeat(64),
                subject = "Translation room",
                description = "A group translating hard books.",
                initialGiftWrapPayloadId = "sdfaer",
                mlsGroupState = "state"
            )
        ),
        canSign = canSign,
    )

    private fun render(
        uiState: ProposeGroupEventUIState,
        assertions: ComposeUiTest.() -> Unit
    ) = runDesktopComposeUiTest(width = 400, height = 1200) {
        setContent {
            DedunTheme {
                ProvideSnackbarHost {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ProposeGroupEventScreen(
                            activeUserPublicKey = "a".repeat(64),
                            chatRoomId = chatRoomId,
                            relayHint = null,
                            initialProposeGroupEventUIState = uiState,
                            chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                            frostSigningRepository =
                                FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY,
                            onNavigateToRouteAndPopUpInclusive = {},
                            onNavigateBack = {},
                        )
                    }
                }
            }
        }

        assertions()
    }
}
