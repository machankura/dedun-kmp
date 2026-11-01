package com.machankura.dedun.compose.ui.composable

import com.machankura.dedun.compose.repository.GroupLightningRepository
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import com.machankura.dedun.compose.database.model.ChatMessage
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatMessage
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalProfileWithFollowing
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.DkgRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.BennuTheme
import com.machankura.dedun.compose.ui.view.state.HomeScreenUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The detail pane follows the selection.
 *
 * On a phone a room is a route, and every push is a back stack entry with a
 * `ViewModelStore` of its own: `viewModel()` in the room screen finds nothing under its
 * key and builds from the factory it was handed. In an expanded window the pane is not
 * pushed. Every room it shows is composed under the home destination's one entry, and
 * `viewModel()` there found the *first* room's view models and handed them back with the
 * factory unread -- so the transcript stayed on the first room opened while the list
 * went on to another. Keying the composition on the room did not help; it is the store
 * that has to be keyed, and [ChatPaneLayoutJvmTest] cannot see that because it never
 * opens a room.
 *
 * The rooms are told apart by what was said in them rather than by their names, because
 * a name is on the list row as well as the transcript's bar, and the row was the one
 * thing that was right.
 *
 * The third test is about the fix rather than the bug. Keying the `viewModel()` calls
 * on the room would pass the first two, and keep every room ever opened -- observers and
 * all -- in the home entry's store for as long as the home screen lives. A store per
 * room is cleared when the pane moves on, and the way to see that from outside is that
 * the room left behind stops collecting its messages.
 */
@OptIn(ExperimentalTestApi::class)
class ChatPaneSelectionJvmTest {

    private val publicKey = "de1a1e64d1c4e0d6bd97b0e73d4dfd0e1ec1cdd1e6d8d0e2b9a7f3c5d0e1a2b3"

    @Test
    fun `opening a second room shows the second room, not the first`() =
        runDesktopComposeUiTest(1000, 900) {
            setContent { Home() }

            open("Alpha")
            awaitLine("Hello from Alpha")

            open("Beta")
            awaitLine("Hello from Beta")
            onNodeWithText("Hello from Alpha").assertDoesNotExist()
        }

    @Test
    fun `and coming back to the first shows the first again`() =
        runDesktopComposeUiTest(1000, 900) {
            setContent { Home() }

            open("Alpha")
            awaitLine("Hello from Alpha")
            open("Beta")
            awaitLine("Hello from Beta")

            open("Alpha")
            awaitLine("Hello from Alpha")
            onNodeWithText("Hello from Beta").assertDoesNotExist()
        }

    @Test
    fun `the room left behind stops listening`() = runDesktopComposeUiTest(1000, 900) {
        setContent { Home() }

        open("Alpha")
        awaitLine("Hello from Alpha")
        assertEquals(setOf(alpha.chatRoom.id), listening)

        open("Beta")
        awaitLine("Hello from Beta")
        // The old view model's scope is cancelled when its store is cleared; the
        // collector notices on its own thread, so this is a wait rather than a read.
        waitUntil(timeoutMillis = 5_000) { listening == setOf(beta.chatRoom.id) }
    }

    /** Taps the room's row. The row is the node with its name that can be clicked. */
    private fun ComposeUiTest.open(subject: String) =
        onNode(hasText(subject) and hasClickAction()).performClick()

    /** Waits for the transcript to say something, which it does from an IO thread. */
    private fun ComposeUiTest.awaitLine(text: String) {
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText(text).assertIsDisplayed()
    }

    @Composable
    private fun Home() {
        BennuTheme {
            ProvideSnackbarHost {
                HomeScreen(
                    activeUserPublicKey = publicKey,
                    initialHomeScreenUIState = HomeScreenUIState.Loaded(profileWithFollowing = profile),
                    onNavigateToRoute = {},
                    onNavigateToDirectMessageDetail = {},
                    onNavigateToChatRoomCreation = {},
                    nostrRepository = FixedProfile,
                    chatRepository = TwoRooms,
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

    private fun room(id: HexKey, subject: String) = LocalChatRoom(
        chatRoom = ChatRoom(
            id = id,
            userPublicKey = publicKey,
            subject = subject,
            description = null,
            initialGiftWrapPayloadId = null,
            mlsGroupState = null,
        ),
    )

    private fun line(id: Long, room: LocalChatRoom, content: String) = LocalChatMessage(
        chatMessage = ChatMessage(
            id = id,
            senderPublicKey = publicKey,
            isUserMessage = true,
            giftWrapPayloadId = null,
            marmotGroupEventId = null,
            marmotInnerEventId = null,
            chatRoomId = room.chatRoom.id,
            content = content,
        ),
        profile = profile.profile,
    )

    private val alpha = room("a".repeat(64), "Alpha")
    private val beta = room("b".repeat(64), "Beta")

    /** The no-op repository, plus the one read the home screen makes of it. */
    private val FixedProfile = object : NostrRepository by NostrRepository.NO_OP_NOSTR_REPOSITORY {
        override suspend fun observeProfileWithFollowing(
            publicKey: String,
        ): Flow<LocalProfileWithFollowing?> = flowOf(profile)
    }

    /** The rooms whose message flow somebody is collecting right now. */
    private val listening: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * The no-op repository, plus the three reads a list beside a transcript makes of it:
     * the rooms, the room that was tapped, and what was said in it. The last of those
     * never completes, the way a database observation never completes, so that its
     * cancellation is something the test can see.
     */
    private val TwoRooms = object : ChatRepository by ChatRepository.NO_OP_CHAT_REPOSITORY {
        override suspend fun observeChatRoomListByPublicKey(
            publicKey: String,
        ): Flow<List<LocalChatRoom>> = flowOf(listOf(alpha, beta))

        override suspend fun getChatRoomByIdentifier(id: HexKey): LocalChatRoom? =
            listOf(alpha, beta).firstOrNull { it.chatRoom.id == id }

        override suspend fun observeChatMessageListByChatRoomId(
            chatRoomId: String,
        ): Flow<List<LocalChatMessage>> = flow {
            listening += chatRoomId
            emit(
                when (chatRoomId) {
                    alpha.chatRoom.id -> listOf(line(1, alpha, "Hello from Alpha"))
                    beta.chatRoom.id -> listOf(line(2, beta, "Hello from Beta"))
                    else -> emptyList()
                }
            )
            awaitCancellation()
        }.onCompletion { listening -= chatRoomId }
    }
}
