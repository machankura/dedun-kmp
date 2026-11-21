package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.machankura.dedun.compose.nostr.GroupNostrProfile
import com.machankura.dedun.compose.nostr.GroupPost
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.ui.composable.GroupSignedWorkFixtures.activeUserPublicKey
import com.machankura.dedun.compose.ui.composable.GroupSignedWorkFixtures.chatRoomId
import com.machankura.dedun.compose.ui.composable.GroupSignedWorkFixtures.post
import com.machankura.dedun.compose.ui.composable.GroupSignedWorkFixtures.profile
import com.machankura.dedun.compose.ui.composable.navigation.routes.AddGroupPostRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.LocalCanSign
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.view.state.GroupPostsUIState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The group's posts on their own screen: newest first and whole, under the
 * group's name, and the composer for a member holding a share.
 * docs/group-nostr-profile-and-posts.md.
 */
@OptIn(ExperimentalTestApi::class)
class GroupPostsScreenJvmTest {

    @Test
    fun `the posts are drawn newest first, under the group's name`() = render(
        state(posts = listOf(post("The second thing", 1_700_000_900), post("The first thing", 1_700_000_000)))
    ) {
        // Newest first, and whole rather than previewed -- a post is the one thing
        // the group signs that exists to be read.
        val newest = onNodeWithText("The second thing").getBoundsInRoot().top
        val oldest = onNodeWithText("The first thing").getBoundsInRoot().top
        assertTrue(newest < oldest, "the posts were drawn oldest first")

        // Under the group's name, not the member's. The whole point of signing a
        // post as the group is that no member can be blamed for it. Once per
        // post: there is no profile card on this screen to add a third.
        onAllNodesWithText("Translation collective").assertCountEquals(2)
        onNodeWithText("Add post").assertIsDisplayed()
    }

    @Test
    fun `a group that has not described itself bylines its posts with the key`() = render(
        state(posts = listOf(post("Something the group said", 1_700_000_000)), groupNostrProfile = null)
    ) {
        onNodeWithText("Something the group said").assertIsDisplayed()
        onNodeWithText(chatRoomId.take(16).chunked(8).joinToString(" ")).assertIsDisplayed()
    }

    @Test
    fun `a group that has posted nothing says so, and offers to post`() {
        var opened: Route? = null

        render(state(posts = emptyList()), onNavigateToRoute = { opened = it }) {
            onNodeWithText("This device holds no posts by this group", substring = true).assertIsDisplayed()
            onNodeWithText("Add post").assertIsDisplayed().performClick()
        }

        assertEquals(AddGroupPostRoute(activeUserPublicKey, chatRoomId, relayHint = null), opened)
    }

    @Test
    fun `a member holding no share of the key reads the posts and is offered no composer`() = render(
        state(posts = listOf(post("Something the group said", 1_700_000_000)), canSign = false)
    ) {
        // A post is the one thing here that is meant to be read by people who
        // were not in the room, so a member without a share reads it like
        // anybody else and simply cannot propose the next one.
        onNodeWithText("Something the group said").assertIsDisplayed()
        onNodeWithText("Add post").assertDoesNotExist()
    }

    @Test
    fun `an identity that cannot sign is offered no composer`() = render(
        state(posts = listOf(post("Something the group said", 1_700_000_000))),
        identityCanSign = false,
    ) {
        // An npub-only profile cannot send the proposal at all. docs/npub-sign-in.md.
        onNodeWithText("Something the group said").assertIsDisplayed()
        onNodeWithText("Add post").assertDoesNotExist()
    }

    @Test
    fun `a group that has posted nothing offers no composer to a member holding no share`() = render(
        state(posts = emptyList(), canSign = false)
    ) {
        onNodeWithText("This device holds no posts by this group", substring = true).assertIsDisplayed()
        onNodeWithText("Add post").assertDoesNotExist()
    }

    private fun state(
        posts: List<GroupPost>,
        groupNostrProfile: GroupNostrProfile? = profile,
        canSign: Boolean = true
    ) = GroupPostsUIState.Loaded(
        localChatRoom = GroupSignedWorkFixtures.localChatRoom(),
        groupPosts = posts,
        groupNostrProfile = groupNostrProfile,
        canSign = canSign
    )

    private fun render(
        uiState: GroupPostsUIState,
        onNavigateToRoute: (Route) -> Unit = {},
        identityCanSign: Boolean = true,
        assertions: ComposeUiTest.() -> Unit
    ) = runDesktopComposeUiTest(width = 400, height = 2000) {
        setContent {
            DedunTheme {
                ProvideSnackbarHost {
                    CompositionLocalProvider(LocalCanSign provides identityCanSign) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        GroupPostsScreen(
                            activeUserPublicKey = activeUserPublicKey,
                            chatRoomId = chatRoomId,
                            relayHint = null,
                            initialGroupPostsUIState = uiState,
                            chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                            onNavigateToRoute = onNavigateToRoute,
                            onNavigateBack = {},
                        )
                    }
                    }
                }
            }
        }

        assertions()
    }
}
