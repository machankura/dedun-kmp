package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.AccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
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
import com.machankura.dedun.compose.ui.composable.navigation.routes.MemberActionUnavailableRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.ProfilePreviewRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.view.state.ChatRoomDetailUIState
import com.machankura.dedun.compose.ui.view.state.MemberAction
import com.machankura.dedun.compose.ui.view.state.MemberActionBlocker
import com.machankura.dedun.compose.ui.view.state.MemberActionOption
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

    /**
     * An admin's row menu offers what the view model decided, and promotion asks
     * first, saying what it gives away. docs/member-admin-promotion.md, Phase 11.
     */
    @Test
    fun `an admin makes a member an admin from the row's menu, after being told what it gives`() {
        render(
            onNavigateToRoute = {},
            state = state().copy(
                adminPublicKeys = setOf(activeUserPublicKey),
                memberActions = mapOf(named to listOf(MemberActionOption(MemberAction.MAKE_ADMIN), MemberActionOption(MemberAction.REMOVE_FROM_GROUP))),
            ),
        ) {
            onAllNodesWithContentDescription("Member actions").assertCountEquals(1)
            onNodeWithContentDescription("Member actions").performClick()
            onNodeWithText("Remove from group").assertExists()
            onNodeWithText("Make admin").performClick()

            onNodeWithText("Make Nomvula an admin?").assertExists()
            onNodeWithText("including you", substring = true).assertExists()
        }
    }

    /**
     * A member who is not an admin sees the menu on every other row, every item
     * marked, and an item opens the screen that says why -- not a dialog, and not
     * the change. docs/member-row-actions.md, Phase 3.
     */
    @Test
    fun `a member who is not an admin sees every action, and an unavailable one explains itself`() {
        var opened: Route? = null
        val notAnAdmin = listOf(MemberActionBlocker.NotAnAdmin(setOf(named)))
        render(
            onNavigateToRoute = { opened = it },
            state = state().copy(
                memberActions = mapOf(
                    named to listOf(
                        MemberActionOption(MemberAction.REMOVE_AS_ADMIN, notAnAdmin + MemberActionBlocker.OnlyAdmin),
                        MemberActionOption(MemberAction.REMOVE_FROM_GROUP, notAnAdmin + MemberActionBlocker.OnlyAdmin),
                    ),
                    stillLoading to listOf(
                        MemberActionOption(MemberAction.MAKE_ADMIN, notAnAdmin),
                        MemberActionOption(MemberAction.REMOVE_FROM_GROUP, notAnAdmin),
                    ),
                ),
            ),
        ) {
            // Every other member's row, in place of the chevron.
            onAllNodesWithContentDescription("Member actions").assertCountEquals(2)
            onAllNodesWithContentDescription("View profile").assertCountEquals(0)

            onAllNodesWithContentDescription("Member actions")[1].performClick()
            onAllNodesWithContentDescription("Why this is unavailable").assertCountEquals(2)
            onNodeWithText("Make admin").performClick()

            // No confirmation: nothing is about to change.
            onNodeWithText("an admin?", substring = true).assertDoesNotExist()
        }

        assertEquals(MemberActionUnavailableRoute(activeUserPublicKey, chatRoomId, stillLoading, MemberAction.MAKE_ADMIN), opened)
    }

    @Test
    fun `your own row keeps the chevron unless stepping down is open`() {
        val me = member(activeUserPublicKey, Profile(publicKey = activeUserPublicKey, displayName = "Me", nostrEventId = "f".repeat(64)))
        val withMe = state().let { it.copy(localChatRoom = it.localChatRoom.copy(localParticipants = listOf(me) + it.localChatRoom.localParticipants)) }

        render(onNavigateToRoute = {}, state = withMe) {
            onAllNodesWithContentDescription("View profile").assertCountEquals(3)
        }
        render(onNavigateToRoute = {}, state = withMe.copy(memberActions = mapOf(activeUserPublicKey to listOf(MemberActionOption(MemberAction.STEP_DOWN))))) {
            onAllNodesWithContentDescription("Member actions").assertCountEquals(1)
            onNodeWithContentDescription("Member actions").performClick()
            onNodeWithText("Step down as admin").assertExists()
            onNodeWithContentDescription("Why this is unavailable").assertDoesNotExist()
        }
    }

    /** The chevron's description, kept on the row itself now that the chevron is gone. D7. */
    @Test
    fun `a row says it opens the profile, without the chevron`() {
        val viewProfile = SemanticsMatcher("clicks to view the profile") {
            val onClick: AccessibilityAction<() -> Boolean>? = it.config.getOrNull(SemanticsActions.OnClick)
            onClick?.label == "View profile"
        }
        render(
            onNavigateToRoute = {},
            state = state().copy(memberActions = mapOf(named to listOf(MemberActionOption(MemberAction.MAKE_ADMIN)))),
        ) {
            onAllNodes(viewProfile).assertCountEquals(2)
        }
    }

    @Test
    fun `an admin's row says so, and a share holder's says that apart`() {
        render(
            onNavigateToRoute = {},
            state = state().copy(adminPublicKeys = setOf(named), shareHolders = setOf(stillLoading)),
        ) {
            onNodeWithText("Admin").assertExists()
            onNodeWithText("Holds a share").assertExists()
        }
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
        state: ChatRoomDetailUIState.Loaded = state(),
        assertions: ComposeUiTest.() -> Unit,
    ) = runDesktopComposeUiTest(width = 400, height = 4000) {
        setContent {
            DedunTheme {
                ProvideSnackbarHost {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ChatRoomDetailScreen(
                            activeUserPublicKey = activeUserPublicKey,
                            chatRoomId = chatRoomId,
                            relayHint = null,
                            initialChatRoomDetailUIState = state,
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
