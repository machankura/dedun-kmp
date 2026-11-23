package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.InboxKey
import com.machankura.dedun.compose.database.model.InboxKeyState
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupInboxRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupInboxConversationRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.GroupInboxSettingsRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.Route
import com.machankura.dedun.compose.ui.composable.widgets.LocalCanSign
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.view.state.GroupInboxConversationUIState
import com.machankura.dedun.compose.ui.view.state.GroupInboxSettingsUIState
import com.machankura.dedun.compose.ui.view.state.GroupInboxUIState

/**
 * The group's inbox on screen (docs/group-inbox.md, Phase 7): its conversations, what it cannot
 * open and why, what an empty one means, and setting one up.
 */
@OptIn(ExperimentalTestApi::class)
class GroupInboxScreenJvmTest {
    private val chatRoomId = "d".repeat(64)
    private val me = "a".repeat(64)
    private val room = LocalChatRoom(ChatRoom(id = chatRoomId, userPublicKey = me, subject = "&Ekklesia", description = null, mlsGroupState = "state"))

    private fun key(generation: Int, state: InboxKeyState = InboxKeyState.ANNOUNCED) = InboxKey(
        chatRoomId = chatRoomId, userPublicKey = me, generation = generation, publicKey = "e".repeat(64),
        secretKey = "5".repeat(64), recoveryData = null, ceremonyId = "c".repeat(64), state = state,
    )

    private fun status(keys: List<InboxKey> = listOf(key(0)), announced: String? = "e".repeat(64), refusal: String? = null) =
        GroupInboxRepository.Status(keys = keys, ceremony = null, announcedKey = announced, relays = emptyList(), setUpRefusal = refusal, waitingOn = emptyList())

    private fun conversation(name: String, text: String, unread: Int = 0) = GroupInboxRepository.ConversationSummary(
        conversationId = "conversation-$name", participants = listOf("b".repeat(64)), names = listOf(name),
        lastText = text, lastAt = 1_700_000_000, lastOutgoing = false, unread = unread,
    )

    private fun renderInbox(state: GroupInboxUIState, onRoute: (Route) -> Unit = {}, assertions: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = 400, height = 1600) {
            setContent {
                DedunTheme {
                    ProvideSnackbarHost {
                        Box(modifier = Modifier.fillMaxSize()) {
                            GroupInboxScreen(
                                activeUserPublicKey = me, chatRoomId = chatRoomId, relayHint = null, initialUIState = state,
                                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY, inboxRepository = GroupInboxRepository.NO_OP,
                                onNavigateToRoute = onRoute, onNavigateBack = {},
                            )
                        }
                    }
                }
            }
            assertions()
        }

    @Test
    fun `a conversation shows who it is with and opens on a tap`() {
        var opened: Route? = null
        renderInbox(GroupInboxUIState.Loaded(room, listOf(conversation("Zola", "Can I join?", unread = 2)), 0, status()), { opened = it }) {
            onNodeWithText("Zola").assertIsDisplayed()
            onNodeWithText("Can I join?").assertIsDisplayed()
            onNodeWithText("2").assertIsDisplayed()
            onNodeWithText("Zola").performClick()
        }
        assertEquals(GroupInboxConversationRoute(me, chatRoomId, null, "conversation-Zola"), opened)
    }

    @Test
    fun `mail no key opens is counted, with why`() = renderInbox(GroupInboxUIState.Loaded(room, emptyList(), 2, status())) {
        onNodeWithText("2 messages this device can't open").assertIsDisplayed()
        onNodeWithText("It was sent to the group's npub", substring = true).assertIsDisplayed()
    }

    @Test
    fun `an empty inbox says nobody has written`() = renderInbox(GroupInboxUIState.Loaded(room, emptyList(), 0, status())) {
        onNodeWithText("Nobody has written to the group yet.").assertIsDisplayed()
    }

    @Test
    fun `an admin who joined after the first key is told why earlier mail is missing`() =
        renderInbox(GroupInboxUIState.Loaded(room, emptyList(), 0, status(keys = listOf(key(2))))) {
            onNodeWithText("Messages sent before you held the group's inbox key aren't on this device.").assertIsDisplayed()
        }

    @Test
    fun `an inbox the group has not announced says nobody can write to it yet`() =
        renderInbox(GroupInboxUIState.Loaded(room, emptyList(), 0, status(keys = listOf(key(0, InboxKeyState.HELD)), announced = null))) {
            onNodeWithText("The group hasn't signed its inbox announcement yet, so nobody can write to it.").assertIsDisplayed()
        }

    @Test
    fun `the settings are a tap away`() {
        var opened: Route? = null
        renderInbox(GroupInboxUIState.Loaded(room, emptyList(), 0, status()), { opened = it }) {
            onNodeWithContentDescription("Inbox settings").performClick()
        }
        assertIs<GroupInboxSettingsRoute>(opened)
    }

    @Test
    fun `a conversation marks the group's replies as the group's`() = runDesktopComposeUiTest(width = 400, height = 1600) {
        setContent {
            DedunTheme {
                ProvideSnackbarHost {
                    GroupInboxConversationScreen(
                        activeUserPublicKey = me, chatRoomId = chatRoomId, relayHint = null, conversationId = "c",
                        initialUIState = GroupInboxConversationUIState.Loaded(
                            room, listOf("Zola"),
                            listOf(
                                GroupInboxConversationUIState.Line("1", "Can I join?", 1_700_000_000, outgoing = false, senderName = "Zola"),
                                GroupInboxConversationUIState.Line("2", "Yes, on Thursday.", 1_700_000_100, outgoing = true, senderName = ""),
                            ),
                        ),
                        chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY, inboxRepository = GroupInboxRepository.NO_OP,
                        onNavigateToRoute = {}, onNavigateBack = {},
                    )
                }
            }
        }
        onNodeWithText("Zola and the group").assertIsDisplayed()
        onNodeWithText("Yes, on Thursday.").assertIsDisplayed()
        onNodeWithText("The group").assertIsDisplayed()
    }

    private fun renderSettings(state: GroupInboxSettingsUIState, canSign: Boolean = true, assertions: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = 400, height = 1600) {
            setContent {
                DedunTheme {
                    ProvideSnackbarHost {
                        CompositionLocalProvider(LocalCanSign provides canSign) {
                            GroupInboxSettingsScreen(
                                activeUserPublicKey = me, chatRoomId = chatRoomId, relayHint = null, initialUIState = state,
                                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY, inboxRepository = GroupInboxRepository.NO_OP,
                                onNavigateToRoute = {}, onNavigateBack = {},
                            )
                        }
                    }
                }
            }
            assertions()
        }

    @Test
    fun `with no key, the settings offer to set one up and say what it means`() =
        renderSettings(GroupInboxSettingsUIState.Loaded(room, status(keys = emptyList(), announced = null))) {
            onNodeWithText("Set up an inbox").assertIsDisplayed()
            onNodeWithText("Every admin holds it and can read what arrives", substring = true).assertIsDisplayed()
            onNodeWithText("Where mail should arrive").assertIsDisplayed()
        }

    @Test
    fun `a refusal is said, and the button is not offered`() =
        renderSettings(GroupInboxSettingsUIState.Loaded(room, status(keys = emptyList(), announced = null, refusal = "Only an admin can set up the group's inbox"))) {
            onNodeWithText("Only an admin can set up the group's inbox").assertIsDisplayed()
            onNodeWithText("Set up an inbox").assertIsNotEnabled()
        }

    @Test
    fun `with a key, the settings show it and offer to rotate it`() =
        renderSettings(GroupInboxSettingsUIState.Loaded(room, status(keys = listOf(key(1), key(0, InboxKeyState.RETIRED))))) {
            onNodeWithText("Key 1, eeeeeeee…eeeeeeee").assertIsDisplayed()
            onNodeWithText("Announced: anyone can write to it").assertIsDisplayed()
            onNodeWithText("Retired, kept to open older mail").assertIsDisplayed()
            onNodeWithText("Rotate the inbox key").assertIsDisplayed()
        }
}
