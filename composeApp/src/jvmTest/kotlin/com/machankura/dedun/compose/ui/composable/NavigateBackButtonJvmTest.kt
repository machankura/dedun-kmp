package com.machankura.dedun.compose.ui.composable

import com.machankura.dedun.compose.repository.GroupLightningRepository
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.DkgRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.view.state.ChatRoomMessagingUIState
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Where the back button is, on the two screens that have to decide.
 *
 * Every other pushed screen draws `NavigateBackButton` unconditionally, and there is
 * nothing to assert about that beyond the compiler: the parameter is not optional. These
 * two take a nullable callback because the same composable is reached two ways, and
 * only the caller knows which:
 *
 *  - the chat room is a destination on a phone and the home screen's detail pane in an
 *    expanded window. In the pane the room list is beside it, and a button that popped
 *    would pop the home screen;
 *  - the "coming soon" placeholder is pushed from a *learn more* and is also where the
 *    navigation machine lands, with the stack cleared, on an error.
 *
 * Read by the description a screen reader would announce, which is also the one thing
 * about the button a test can check that the type system does not.
 */
@OptIn(ExperimentalTestApi::class)
class NavigateBackButtonJvmTest {

    @Test
    fun `a pushed chat room has a back button, and it pops`() = runDesktopComposeUiTest(400, 900) {
        var popped = false
        setContent { Chat(onNavigateBack = { popped = true }) }

        onNodeWithContentDescription("Back").assertIsDisplayed().performClick()
        assertTrue(popped, "the button should have called the host's pop")
    }

    @Test
    fun `the same chat room beside its list has none`() = runDesktopComposeUiTest(1000, 900) {
        setContent { Chat(onNavigateBack = null) }

        onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test
    fun `a pushed placeholder has a back button`() = runDesktopComposeUiTest(400, 900) {
        setContent { Placeholder(onNavigateBack = {}) }

        onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun `a placeholder at the root of the stack has none`() = runDesktopComposeUiTest(400, 900) {
        setContent { Placeholder(onNavigateBack = null) }

        onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Composable
    private fun Chat(onNavigateBack: (() -> Unit)?) {
        DedunTheme {
            ProvideSnackbarHost {
                ChatRoomMessagingScreen(
                    activeUserPublicKey = "a".repeat(64),
                    chatRoomId = "b".repeat(64),
                    relayHint = null,
                    initialChatRoomMessagingUIState = ChatRoomMessagingUIState.Loaded(
                        localChatRoom = LocalChatRoom(
                            chatRoom = ChatRoom(
                                id = "b".repeat(64),
                                userPublicKey = "a".repeat(64),
                                subject = "Reading group",
                                description = null,
                                initialGiftWrapPayloadId = null,
                                mlsGroupState = null,
                            ),
                        ),
                    ),
                    nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
                    chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                    frostSigningRepository = FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY,
                    dkgRepository = DkgRepository.NO_OP_DKG_REPOSITORY,
                    lightningRepository = GroupLightningRepository.NO_OP_GROUP_LIGHTNING_REPOSITORY,
                    onNavigateToRouteAndPopUpInclusive = {},
                    onNavigateToRoute = {},
                    onNavigateBack = onNavigateBack,
                )
            }
        }
    }

    @Composable
    private fun Placeholder(onNavigateBack: (() -> Unit)?) {
        DedunTheme {
            ProvideSnackbarHost {
                ImplementationPendingScreen(text = "Learn more", onNavigateBack = onNavigateBack)
            }
        }
    }
}
