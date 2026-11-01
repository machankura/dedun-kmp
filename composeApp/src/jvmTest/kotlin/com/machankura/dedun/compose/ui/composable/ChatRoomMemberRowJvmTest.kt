package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.intermdiate.LocalParticipant
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.composable.GroupSignedWorkFixtures.activeUserPublicKey
import com.machankura.dedun.compose.ui.composable.GroupSignedWorkFixtures.chatRoomId
import com.machankura.dedun.compose.ui.composable.navigation.routes.ProfilePreviewRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.BennuTheme
import com.machankura.dedun.compose.ui.view.state.ChatRoomDetailUIState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The member rows on the room's screen, and what one opens.
 *
 * A row is a promise about the screen behind it, and this one was a stub for long
 * enough that the promise is worth pinning: a member opens the profile preview,
 * keyed by the member's public key, with the active identity beside it so the
 * preview can say whether a chat with them already exists.
 *
 * Two members, because the screen names them differently and the row has to open
 * the same screen either way. One has a profile and is named by it. The other has
 * none yet -- the "LOADING..." row `MemberProfileSync` exists for -- and is named
 * by the key; that member is the reason the row opens the preview rather than the
 * profile detail, which is keyed by a kind 0's event id and has nothing to open for
 * a member whose kind 0 has not arrived.
 */
@OptIn(ExperimentalTestApi::class)
class ChatRoomMemberRowJvmTest {

    private val named = "b".repeat(64)
    private val stillLoading = "c".repeat(64)

    @Test
    fun `a member with a profile opens the preview by their key`() {
        var opened: Route? = null

        render(onNavigateToRoute = { opened = it }) {
            onNodeWithText("Nomvula").performClick()
        }

        assertEquals(ProfilePreviewRoute(activeUserPublicKey, named), opened)
    }

    @Test
    fun `a member whose profile has not arrived opens the preview by their key too`() {
        var opened: Route? = null

        render(onNavigateToRoute = { opened = it }) {
            onNodeWithText(stillLoading).performClick()
        }

        assertEquals(ProfilePreviewRoute(activeUserPublicKey, stillLoading), opened)
    }

    private fun member(publicKey: String, profile: Profile?) = LocalParticipant(
        participant = Participant(
            participantPublicKey = publicKey,
            chatRoomId = chatRoomId,
            relayHint = null,
        ),
        profile = profile,
    )

    private fun state() = ChatRoomDetailUIState.Loaded(
        localChatRoom = GroupSignedWorkFixtures.localChatRoom().copy(
            localParticipants = listOf(
                member(
                    named,
                    Profile(
                        publicKey = named,
                        displayName = "Nomvula",
                        nostrEventId = "e".repeat(64),
                    ),
                ),
                member(stillLoading, profile = null),
            ),
        ),
    )

    /**
     * The screen at a phone's width and a window tall enough to compose the whole
     * of it, as `GroupSignedWorkRowsJvmTest` renders it: the members are near the
     * bottom, and a `LazyColumn` does not lay out what it would not show.
     */
    private fun render(
        onNavigateToRoute: (Route) -> Unit,
        assertions: ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(width = 400, height = 4000) {
        setContent {
            BennuTheme {
                ProvideSnackbarHost {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ChatRoomDetailScreen(
                            activeUserPublicKey = activeUserPublicKey,
                            chatRoomId = chatRoomId,
                            relayHint = null,
                            initialChatRoomDetailUIState = state(),
                            nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
                            chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                            onNavigateToRoute = onNavigateToRoute,
                            onPopBackToRoute = {},
                            onNavigateBack = {},
                        )
                    }
                }
            }
        }

        assertions()
    }
}
