package com.machankura.dedun.compose.ui.view.model

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.view.state.ChatRoomDetailUIState

/**
 * The room detail screen's view model, where the repository's refusals have to
 * become something the screen can say. See docs/member-admin-promotion.md.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRoomDetailViewModelJvmTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val room = LocalChatRoom(
        chatRoom = ChatRoom(id = "room", userPublicKey = "me", subject = "A room", description = null, mlsGroupState = null),
        localParticipants = emptyList(),
    )

    private fun viewModel(chatRepository: ChatRepository) = ChatRoomDetailViewModel(
        chatRoomId = "room",
        activeUserPublicKey = "me",
        relayHint = null,
        initialChatRoomDetailUIState = ChatRoomDetailUIState.Loading,
        nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
        chatRepository = chatRepository,
    )

    /**
     * Phase 10's first step. The refusal used to be thrown inside the view model's
     * coroutine and caught by nothing: never shown, and on Android a crash.
     */
    @Test
    fun `an admin's refused leave reaches the screen in the refusal's own words`() = runBlocking {
        val refusal = "An admin cannot leave; hand over admin first"
        val repository = object : ChatRepository by ChatRepository.NO_OP_CHAT_REPOSITORY {
            override suspend fun leaveChatRoom(localChatRoom: LocalChatRoom, onCompletion: () -> Unit) {
                throw IllegalStateException(refusal)
            }
        }
        val reported = CompletableDeferred<String?>()
        var poppedBack = false

        viewModel(repository).leaveGroup(
            localChatRoom = room,
            onPopBackToRoute = { poppedBack = true },
            onFailed = { reported.complete(it) },
        )

        assertEquals(refusal, withTimeout(5_000) { reported.await() })
        assertEquals(false, poppedBack, "the screen was left although nothing left the group")
    }
}
