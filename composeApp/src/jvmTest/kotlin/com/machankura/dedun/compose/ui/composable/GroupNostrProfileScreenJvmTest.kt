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
import com.vitorpamplona.quartz.nip01Core.metadata.UserMetadata
import com.machankura.dedun.compose.nostr.GroupNostrProfile
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.ui.composable.GroupSignedWorkFixtures.activeUserPublicKey
import com.machankura.dedun.compose.ui.composable.GroupSignedWorkFixtures.chatRoomId
import com.machankura.dedun.compose.ui.composable.GroupSignedWorkFixtures.profile
import com.machankura.dedun.compose.ui.composable.navigation.routes.BroadcastGroupSignedEventRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.EditGroupNostrProfileRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.LocalCanSign
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.view.state.GroupNostrProfileUIState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The group's nostr profile on its own screen: what is drawn, what the broadcast
 * under it sends, and who is offered the editor. docs/group-nostr-profile-and-posts.md.
 */
@OptIn(ExperimentalTestApi::class)
class GroupNostrProfileScreenJvmTest {

    @Test
    fun `the profile is drawn whole`() = render(state(groupNostrProfile = profile)) {
        onNodeWithText("Translation collective").assertIsDisplayed()
        onNodeWithText("group@example.com").assertIsDisplayed()
        onNodeWithText("A group translating hard books into Sesotho.").assertIsDisplayed()
        onAllNodesWithText("Signed by the group on", substring = true).assertCountEquals(1)
    }

    @Test
    fun `the broadcast under the card sends the profile's event, share or no share`() {
        var opened: Route? = null

        render(state(groupNostrProfile = profile, canSign = false), onNavigateToRoute = { opened = it }) {
            // One, under the card it sends. Not gated on a share: the signature is
            // the group's, whoever repeats it (D4).
            val buttons = onAllNodesWithText("Broadcast")
            buttons.assertCountEquals(1)
            val card = onNodeWithText("group@example.com").getBoundsInRoot().top
            assertTrue(card < buttons[0].getBoundsInRoot().top, "the broadcast is not under the card")

            buttons[0].performClick()
        }

        assertEquals(
            BroadcastGroupSignedEventRoute(
                activeUserPublicKey = activeUserPublicKey,
                chatRoomId = chatRoomId,
                relayHint = null,
                signedEventId = profile.signedEvent.id
            ),
            opened
        )
    }

    @Test
    fun `nothing held, nothing to broadcast`() = render(state(groupNostrProfile = null)) {
        onAllNodesWithText("Broadcast").assertCountEquals(0)
    }

    /** docs/group-nostr-profile-and-posts.md, D6. */
    @Test
    fun `a lightning address on the group's profile is not shown`() {
        val withAddress = GroupNostrProfile(
            signedEvent = profile.signedEvent,
            metadata = UserMetadata().apply {
                displayName = "Translation collective"
                lud16 = "group@getalby.com"
            }
        )

        render(state(groupNostrProfile = withAddress)) {
            onNodeWithText("Translation collective").assertIsDisplayed()
            onNodeWithText("group@getalby.com", substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun `a member holding a share of the key is offered the editor`() {
        var opened: Route? = null

        render(state(groupNostrProfile = profile), onNavigateToRoute = { opened = it }) {
            onNodeWithText("Edit Nostr profile").assertIsDisplayed().performClick()
        }

        assertEquals(
            EditGroupNostrProfileRoute(activeUserPublicKey, chatRoomId, relayHint = null),
            opened
        )
    }

    @Test
    fun `a member holding no share of the key reads the profile and is offered no edit`() = render(
        state(groupNostrProfile = profile, canSign = false)
    ) {
        // The profile is worth reading whoever is looking; proposing one needs a
        // share, and `proposeSigningBatch` throws without one.
        onNodeWithText("Translation collective").assertIsDisplayed()
        onNodeWithText("Edit Nostr profile").assertDoesNotExist()
    }

    @Test
    fun `an identity that cannot sign is offered no edit`() = render(
        state(groupNostrProfile = profile),
        identityCanSign = false,
    ) {
        // An npub-only profile cannot send the proposal at all. docs/npub-sign-in.md.
        onNodeWithText("Translation collective").assertIsDisplayed()
        onNodeWithText("Edit Nostr profile").assertDoesNotExist()
    }

    @Test
    fun `a device holding no profile says so, and offers to write one`() = render(
        state(groupNostrProfile = null)
    ) {
        // "Holds no profile", not "has none": a profile signed before this member
        // joined is not passed on (D8).
        onNodeWithText("This device holds no profile for this group", substring = true)
            .assertIsDisplayed()
        onNodeWithText("Edit Nostr profile").assertIsDisplayed()
    }

    @Test
    fun `a device holding no profile offers no editor to a member holding no share`() = render(
        state(groupNostrProfile = null, canSign = false)
    ) {
        onNodeWithText("This device holds no profile for this group", substring = true)
            .assertIsDisplayed()
        onNodeWithText("Edit Nostr profile").assertDoesNotExist()
    }

    private fun state(
        groupNostrProfile: GroupNostrProfile?,
        canSign: Boolean = true
    ) = GroupNostrProfileUIState.Loaded(
        localChatRoom = GroupSignedWorkFixtures.localChatRoom(),
        groupNostrProfile = groupNostrProfile,
        canSign = canSign
    )

    /**
     * The screen at a phone's width. The state is passed in, so the NO_OP
     * repository is never asked for anything: `initiate` only runs for a screen
     * that arrives loading.
     */
    private fun render(
        uiState: GroupNostrProfileUIState,
        onNavigateToRoute: (Route) -> Unit = {},
        identityCanSign: Boolean = true,
        assertions: ComposeUiTest.() -> Unit
    ) = runDesktopComposeUiTest(width = 400, height = 2000) {
        setContent {
            DedunTheme {
                ProvideSnackbarHost {
                    CompositionLocalProvider(LocalCanSign provides identityCanSign) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        GroupNostrProfileScreen(
                            activeUserPublicKey = activeUserPublicKey,
                            chatRoomId = chatRoomId,
                            relayHint = null,
                            initialGroupNostrProfileUIState = uiState,
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
