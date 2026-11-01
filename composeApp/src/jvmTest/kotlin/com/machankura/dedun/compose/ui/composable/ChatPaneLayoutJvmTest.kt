package com.machankura.dedun.compose.ui.composable

import com.machankura.dedun.compose.repository.GroupLightningRepository
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalProfileWithFollowing
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.DkgRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.BennuTheme
import com.machankura.dedun.compose.ui.view.state.HomeScreenUIState
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import kotlin.test.Test

/**
 * The chat list-detail split, measured in windows of the widths the phase names.
 *
 * Three claims, and none of them can be read off the source:
 *
 *  - below expanded the home screen is unchanged. That is the promise that let the split
 *    land as one commit -- the chat room is a navigation destination reached from eleven
 *    places, and on a phone it stays one;
 *  - at expanded and above the list pane is exactly the width
 *    `calculatePaneScaffoldDirective` gives it, so a hand-built pair measures the same as
 *    a `ListDetailPaneScaffold` would;
 *  - the detail pane says what it is for while nothing is selected, rather than being an
 *    unexplained empty half of the window.
 *
 * The repositories are the no-op ones with the two reads this screen makes delegated to a
 * fixed answer. Kotlin's interface delegation makes that ten lines rather than a
 * reimplementation of two large interfaces, and it keeps the test about layout: the screen
 * asks for a profile and a room list, and what matters here is where they are drawn.
 */
@OptIn(ExperimentalTestApi::class)
class ChatPaneLayoutJvmTest {

    private val publicKey = "de1a1e64d1c4e0d6bd97b0e73d4dfd0e1ec1cdd1e6d8d0e2b9a7f3c5d0e1a2b3"
    private val emptyDetail = "Pick a conversation to read it here."

    @Test
    fun `a phone window is one pane, as it has always been`() = runDesktopComposeUiTest(400, 900) {
        setContent { Home() }

        onNodeWithText(emptyDetail).assertDoesNotExist()
    }

    @Test
    fun `a medium window is still one pane, because a transcript is dense`() =
        runDesktopComposeUiTest(700, 900) {
            setContent { Home() }

            // M3: no two panes in a medium window with high information density.
            onNodeWithText(emptyDetail).assertDoesNotExist()
        }

    @Test
    fun `an expanded window splits, with the directive's 360dp list pane`() =
        runDesktopComposeUiTest(1000, 900) {
            setContent { Home() }

            onNodeWithText(emptyDetail).assertIsDisplayed()
            onNodeWithTag(ChatListPaneTag).assertWidthIsEqualTo(360.dp)
        }

    @Test
    fun `a large window widens the list pane to 412dp`() = runDesktopComposeUiTest(1400, 900) {
        setContent { Home() }

        onNodeWithText(emptyDetail).assertIsDisplayed()
        onNodeWithTag(ChatListPaneTag).assertWidthIsEqualTo(412.dp)
    }

    @androidx.compose.runtime.Composable
    private fun Home() {
        BennuTheme {
            // Required, and deliberately so: `LocalSnackbarHostState` throws rather than
            // defaulting, because a default would make every `notify` on a screen that
            // forgot the host a silent no-op.
            ProvideSnackbarHost {
            HomeScreen(
                activeUserPublicKey = publicKey,
                initialHomeScreenUIState = HomeScreenUIState.Loaded(profileWithFollowing = profile),
                onNavigateToRoute = {},
                onNavigateToDirectMessageDetail = {},
                onNavigateToChatRoomCreation = {},
                nostrRepository = FixedProfile,
                chatRepository = NoRooms,
                frostSigningRepository = FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY,
                dkgRepository = DkgRepository.NO_OP_DKG_REPOSITORY,
                lightningRepository = GroupLightningRepository.NO_OP_GROUP_LIGHTNING_REPOSITORY,
            )
            }
        }
    }

    private val profile = LocalProfileWithFollowing(
        nostrEvent = NostrEvent(
            id = "aa11bb22cc33dd44ee55ff6600778899aabbccddeeff00112233445566778899",
            pubKey = publicKey,
            content = "",
            tags = emptyArray(),
            sig = "",
            kind = TextNoteEvent.KIND,
        ),
        profile = Profile(
            publicKey = publicKey,
            displayName = "Reader",
            nostrEventId = "aa11bb22cc33dd44ee55ff6600778899aabbccddeeff00112233445566778899",
        ),
        following = emptyList(),
    )

    /** The no-op repository, plus the one read the home screen makes of it. */
    private val FixedProfile = object : NostrRepository by NostrRepository.NO_OP_NOSTR_REPOSITORY {
        override suspend fun observeProfileWithFollowing(
            publicKey: String,
        ): Flow<LocalProfileWithFollowing?> = flowOf(profile)
    }

    /** The no-op repository, plus the one read the room list makes of it. */
    private val NoRooms = object : ChatRepository by ChatRepository.NO_OP_CHAT_REPOSITORY {
        override suspend fun observeChatRoomListByPublicKey(
            publicKey: String,
        ): Flow<List<LocalChatRoom>> = flowOf(emptyList())
    }
}
