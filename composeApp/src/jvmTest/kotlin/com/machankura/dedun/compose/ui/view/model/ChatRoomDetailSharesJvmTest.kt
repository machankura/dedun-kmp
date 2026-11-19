package com.machankura.dedun.compose.ui.view.model

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
import com.machankura.dedun.compose.ui.view.state.SharesSummary
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The room detail screen's Shares section follows the group while it is open
 * (docs/rotation-waits-for-key-state.md, Phase 3): the rotate button the last
 * rotation held comes back when the group signs, without the screen being left
 * and opened again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRoomDetailSharesJvmTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val chatRoomId = "c0ffee00".repeat(8)
    private val me = "a".repeat(64)
    private val localChatRoom = LocalChatRoom(
        chatRoom = ChatRoom(id = chatRoomId, userPublicKey = me, subject = "Admins", description = null, mlsGroupState = null)
    )

    private fun summary(rotation: SharesSummary.Rotation) =
        SharesSummary(generation = 1, members = emptyList(), pending = emptyList(), rotation = rotation, canAskToBeRepaired = false)

    private class Repository(val room: LocalChatRoom, var shares: SharesSummary) :
        ChatRepository by ChatRepository.NO_OP_CHAT_REPOSITORY {
        val inputs = MutableSharedFlow<Unit>(replay = 1)
        var reads = 0
        override suspend fun getChatRoomByIdentifier(id: HexKey): LocalChatRoom? = room.takeIf { id == room.chatRoom.id }
        override suspend fun sharesSummary(localChatRoom: LocalChatRoom): SharesSummary { reads++; return shares }
        override fun observeSharesInputs(chatRoomId: String): Flow<Unit> = inputs
    }

    private fun viewModel(repository: Repository) = ChatRoomDetailViewModel(
        chatRoomId = chatRoomId,
        activeUserPublicKey = me,
        relayHint = null,
        initialChatRoomDetailUIState = ChatRoomDetailUIState.Loading,
        nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
        chatRepository = repository,
    )

    private suspend fun ChatRoomDetailViewModel.awaitRotation(expected: SharesSummary.Rotation) = withTimeout(5_000) {
        while ((chatRoomDetailUIState as? ChatRoomDetailUIState.Loaded)?.shares?.rotation != expected) delay(10)
    }

    @Test
    fun `the rotate button comes back when the group signs, with the screen open`() = runBlocking<Unit> {
        val held = SharesSummary.Rotation.Held(SharesSummary.Rotation.Reason.AWAITING_SIGNATURES)
        val repository = Repository(localChatRoom, summary(held))
        val viewModel = viewModel(repository)
        viewModel.initiateChatRoomDetail()
        viewModel.awaitRotation(held)

        // The group's signature lands in the database while the screen is open.
        repository.shares = summary(SharesSummary.Rotation.Available)
        repository.inputs.emit(Unit)
        viewModel.awaitRotation(SharesSummary.Rotation.Available)
    }

    @Test
    fun `a burst of signing messages is read once it settles`() = runBlocking<Unit> {
        val repository = Repository(localChatRoom, summary(SharesSummary.Rotation.Available))
        val viewModel = viewModel(repository)
        viewModel.initiateChatRoomDetail()
        viewModel.awaitRotation(SharesSummary.Rotation.Available)
        delay(ChatRoomDetailViewModel.SHARES_SETTLE_MILLIS * 2)
        val before = repository.reads

        repeat(20) { repository.inputs.emit(Unit) }
        delay(ChatRoomDetailViewModel.SHARES_SETTLE_MILLIS * 3)

        val reads = repository.reads - before
        assertTrue(reads in 1..2, "20 changes in a burst read the section $reads times")
        assertEquals(SharesSummary.Rotation.Available, (viewModel.chatRoomDetailUIState as ChatRoomDetailUIState.Loaded).shares?.rotation)
    }
}
