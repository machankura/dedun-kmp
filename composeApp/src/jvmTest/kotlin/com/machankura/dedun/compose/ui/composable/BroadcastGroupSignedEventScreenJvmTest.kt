package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.displayUrl
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.exceptions.NostrPublishException
import com.machankura.dedun.compose.network.dto.RelayDTO
import com.machankura.dedun.compose.network.relays.EventPublishTransport
import com.machankura.dedun.compose.network.relays.NostrPublishResult
import com.machankura.dedun.compose.network.sockets.NostrIncomingMessage
import com.machankura.dedun.compose.nostr.Relays
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.view.state.BroadcastGroupSignedEventUIState
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * What the broadcast screen shows before, and after, the button.
 *
 * Before: the event named as the signing screen would name it, the app's own
 * publishing relays already in the list, and a way to add one or take one out. After:
 * each relay's answer under its own row, in the relay's words where it gave
 * any, and the sum above them -- because the sum is what the member came for and
 * the words are what they can act on.
 */
@OptIn(ExperimentalTestApi::class)
class BroadcastGroupSignedEventScreenJvmTest {

    private val chatRoomId = "d4c3b2a1".repeat(8)

    private val one = RelayUrlNormalizer.normalize("wss://relay.one.example")
    private val two = RelayUrlNormalizer.normalize("wss://relay.two.example")

    /** A pool that answers as told and never opens a socket. */
    private class FakeTransport(
        private val answers: List<NostrPublishResult>
    ) : EventPublishTransport {
        override suspend fun publishEvent(
            nostrEvent: NostrEvent,
            relays: Set<RelayDTO>
        ): Flow<NostrPublishResult> = flowOf(*answers.toTypedArray())
    }

    @Test
    fun `the event is named, the app's relays are filled in, and the JSON is there to copy`() =
        render(state()) {
            onNodeWithText("Post").assertIsDisplayed()
            onNodeWithText("The first chapter is out.").assertIsDisplayed()

            // The app's publish set, as hosts: Dedun did not bring back the group's
            // relay lists (docs/group-nostr-profile-and-posts.md, D1).
            val firstSeeded = Relays.eventPublishRelaySet.first().displayUrl()
            onNodeWithText(firstSeeded).assertIsDisplayed()
            onNodeWithText("The app's own publishing relays are filled in", substring = true).assertIsDisplayed()

            onNodeWithText("Broadcast").assertIsDisplayed()
            onNodeWithText("Copy raw JSON").assertIsDisplayed()

            // The relays come before the JSON: the list is the screen's job and
            // the JSON is for whoever would rather use a different tool.
            val relays = onNodeWithText(firstSeeded).getBoundsInRoot().top
            val json = onNodeWithText("The signed event").getBoundsInRoot().top
            assertTrue(relays < json, "the JSON climbed above the relay list")
        }

    @Test
    fun `a relay can be added, and a bad one is refused by name`() = render(state()) {
        onNodeWithText("Relay URL").performTextInput("wss://relay.three.example")
        onNodeWithContentDescription("Add relay").performClick()
        onNodeWithText("relay.three.example").assertIsDisplayed()

        onNodeWithText("Relay URL").performTextInput("not a relay")
        onNodeWithContentDescription("Add relay").performClick()
        onNodeWithText("That isn't a relay address.", substring = true).assertIsDisplayed()
    }

    @Test
    fun `after the button, each relay says what it answered and the sum sits above them`() =
        render(
            uiState = state(),
            transport = FakeTransport(
                listOf(
                    NostrPublishResult(
                        result = NostrIncomingMessage.OkMessage(eventId = "b".repeat(64), success = true),
                        relayUrl = one.url,
                    ),
                    NostrPublishResult(
                        result = NostrIncomingMessage.OkMessage(
                            eventId = "b".repeat(64),
                            success = false,
                            message = "blocked: not on the allow list",
                        ),
                        error = NostrPublishException(message = "blocked: not on the allow list"),
                        relayUrl = two.url,
                    ),
                )
            )
        ) {
            emptyTheList()
            listOf(one, two).forEach { relay ->
                onNodeWithText("Relay URL").performTextInput(relay.url)
                onNodeWithContentDescription("Add relay").performClick()
            }

            onNodeWithContentDescription("Broadcast to these relays").performClick()

            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithText("Accepted by 1 of 2 relays.").fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithText("Accepted").assertIsDisplayed()
            onNodeWithText("Rejected: blocked: not on the allow list").assertIsDisplayed()

            // The answer under the relay it came from, not in a list of its own.
            val relayOne = onNodeWithText("relay.one.example").getBoundsInRoot().top
            val accepted = onNodeWithText("Accepted").getBoundsInRoot().top
            val relayTwo = onNodeWithText("relay.two.example").getBoundsInRoot().top
            val rejected = onNodeWithText("Rejected: blocked: not on the allow list").getBoundsInRoot().top
            assertTrue(relayOne < accepted && accepted < relayTwo, "the OK is not under its relay")
            assertTrue(relayTwo < rejected, "the rejection is not under its relay")
        }

    @Test
    fun `an empty list asks nobody and says to add one`() = render(state()) {
        emptyTheList()

        onNodeWithText("No relays to broadcast to. Add one above.").assertIsDisplayed()
    }

    /** Removes every relay the screen seeded, one row at a time. */
    private fun ComposeUiTest.emptyTheList() {
        while (onAllNodesWithContentDescription("Remove relay").fetchSemanticsNodes().isNotEmpty()) {
            onAllNodesWithContentDescription("Remove relay")[0].performClick()
            waitForIdle()
        }
    }

    private fun signedEvent() = GroupSignedEvent(
        id = "b".repeat(64),
        chatRoomId = chatRoomId,
        publicKey = chatRoomId,
        kind = TextNoteEvent.KIND,
        tags = emptyArray(),
        content = "The first chapter is out.",
        signature = "f".repeat(128),
        createdAt = Instant.fromEpochSeconds(1_700_000_000),
    )

    private fun state() = BroadcastGroupSignedEventUIState.Loaded(
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
        signedEvent = signedEvent(),
    )

    private fun render(
        uiState: BroadcastGroupSignedEventUIState,
        transport: EventPublishTransport = FakeTransport(emptyList()),
        assertions: ComposeUiTest.() -> Unit
    ) = runDesktopComposeUiTest(width = 400, height = 3000) {
        setContent {
            DedunTheme {
                ProvideSnackbarHost {
                    Box(modifier = Modifier.fillMaxSize()) {
                        BroadcastGroupSignedEventScreen(
                            activeUserPublicKey = "a".repeat(64),
                            chatRoomId = chatRoomId,
                            relayHint = null,
                            signedEventId = "b".repeat(64),
                            initialBroadcastGroupSignedEventUIState = uiState,
                            chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
                            publishTransport = transport,
                            onNavigateBack = {}
                        )
                    }
                }
            }
        }

        assertions()
    }
}
