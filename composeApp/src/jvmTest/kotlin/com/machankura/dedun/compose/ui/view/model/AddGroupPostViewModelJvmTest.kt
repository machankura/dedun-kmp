package com.machankura.dedun.compose.ui.view.model

import androidx.compose.foundation.text.input.TextFieldState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip10Notes.TextNoteEvent
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.ui.composable.GroupSignedWorkFixtures
import com.machankura.dedun.compose.ui.view.state.AddGroupPostUIState

/**
 * What the composer puts to the group's quorum, and what it will not.
 * docs/group-nostr-profile-and-posts.md, Phase 6.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddGroupPostViewModelJvmTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val asked = mutableListOf<Pair<Kind, String>>()

    private val repository = object : FrostSigningRepository by FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY {
        override suspend fun proposeSigning(
            localChatRoom: LocalChatRoom,
            userPublicKey: HexKey,
            kind: Kind,
            tags: Array<Array<String>>,
            content: String,
        ): FrostSigningSession {
            asked += kind to content
            return FrostSigningSession(
                id = "5e55".repeat(16),
                chatRoomId = localChatRoom.chatRoom.id,
                coordinatorPublicKey = userPublicKey,
                userPublicKey = userPublicKey,
                dkgSessionId = "c".repeat(64),
                threshold = 2,
                participantCount = 3,
                signerId = 0,
            )
        }
    }

    private val viewModel = AddGroupPostViewModel(
        chatRoomId = GroupSignedWorkFixtures.chatRoomId,
        activeUserPublicKey = GroupSignedWorkFixtures.activeUserPublicKey,
        relayHint = null,
        initialAddGroupPostUIState = AddGroupPostUIState.Loading,
        chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
        frostSigningRepository = repository,
    )

    private fun propose(note: String): String = runBlocking {
        val outcome = CompletableDeferred<String>()
        viewModel.proposePost(
            localChatRoom = GroupSignedWorkFixtures.localChatRoom(),
            noteField = TextFieldState(note),
            onSuccess = { outcome.complete("proposed $it") },
            onEmpty = { outcome.complete("empty") },
            onFailure = { outcome.complete("failed") },
        )
        withTimeout(5_000) { outcome.await() }
    }

    @Test
    fun `a note is proposed as a bare kind 1, trimmed`() {
        assertEquals("proposed ${"5e55".repeat(16)}", propose("  We meet on Thursday \n"))
        assertEquals(listOf(TextNoteEvent.KIND to "We meet on Thursday"), asked)
    }

    @Test
    fun `a blank note is not proposed`() {
        // A kind:1 is not replaceable, and every member's device would decline a
        // blank one anyway (GroupStatementRefusal).
        assertEquals("empty", propose(" \n  "))
        assertTrue(asked.isEmpty(), "the group was asked to sign nothing")
    }
}
