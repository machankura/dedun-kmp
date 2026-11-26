package com.machankura.dedun.compose.ui.view.model

import androidx.compose.foundation.text.input.TextFieldState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import com.vitorpamplona.quartz.nip01Core.metadata.MetadataEvent
import com.vitorpamplona.quartz.nip01Core.metadata.UserMetadata
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.GroupSignedEvent
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.GroupNostrProfile
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.ui.composable.GroupSignedWorkFixtures
import com.machankura.dedun.compose.ui.view.state.EditGroupNostrProfileUIState

/**
 * What the profile form puts to the group's quorum: the event, not the screen.
 * docs/group-nostr-profile-and-posts.md, Phase 5.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditGroupNostrProfileViewModelJvmTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class Proposed(val kind: Kind, val content: String)

    /** A repository that records what it was asked to sign and opens a session for it. */
    private fun recording(proposed: CompletableDeferred<Proposed>) =
        object : FrostSigningRepository by FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY {
            override suspend fun proposeSigning(
                localChatRoom: LocalChatRoom,
                userPublicKey: HexKey,
                kind: Kind,
                tags: Array<Array<String>>,
                content: String,
            ): FrostSigningSession {
                proposed.complete(Proposed(kind, content))
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

    private fun propose(
        latest: GroupNostrProfile?,
        name: String,
        about: String = "",
        nip05: String = "",
    ): Pair<Proposed, String> = runBlocking {
        val proposed = CompletableDeferred<Proposed>()
        val sessionId = CompletableDeferred<String>()
        val viewModel = EditGroupNostrProfileViewModel(
            chatRoomId = GroupSignedWorkFixtures.chatRoomId,
            activeUserPublicKey = GroupSignedWorkFixtures.activeUserPublicKey,
            relayHint = null,
            initialEditGroupNostrProfileUIState = EditGroupNostrProfileUIState.Loading,
            chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
            frostSigningRepository = recording(proposed),
        )

        viewModel.proposeNostrProfile(
            localChatRoom = GroupSignedWorkFixtures.localChatRoom(),
            groupNostrProfile = latest,
            nameField = TextFieldState(name),
            aboutField = TextFieldState(about),
            pictureField = TextFieldState(""),
            websiteField = TextFieldState(""),
            nip05Field = TextFieldState(nip05),
            onSuccess = { sessionId.complete(it) },
            onFailure = { sessionId.completeExceptionally(AssertionError("the proposal failed")) },
        )

        withTimeout(5_000) { proposed.await() to sessionId.await() }
    }

    private fun existing(content: String) = GroupNostrProfile(
        signedEvent = GroupSignedEvent(
            id = "e".repeat(64),
            chatRoomId = GroupSignedWorkFixtures.chatRoomId,
            publicKey = GroupSignedWorkFixtures.chatRoomId,
            kind = MetadataEvent.KIND,
            tags = emptyArray(),
            content = content,
            signature = "f".repeat(128),
            createdAt = Instant.fromEpochSeconds(1_700_000_000),
        ),
        metadata = UserMetadata(),
    )

    private fun read(content: String): UserMetadata = assertNotNull(
        MetadataEvent("", "", 0, emptyArray(), content, "").contactMetaData()
    )

    @Test
    fun `the form proposes a kind 0 with the name under both keys, and hands over its session`() {
        val (proposed, sessionId) = propose(latest = null, name = "  Translation collective ", about = "Hard books")

        assertEquals(MetadataEvent.KIND, proposed.kind)
        val metadata = read(proposed.content)
        assertEquals("Translation collective", metadata.name, "trimmed")
        assertEquals("Translation collective", metadata.displayName)
        assertEquals("Hard books", metadata.about)
        assertEquals("5e55".repeat(16), sessionId)
    }

    @Test
    fun `an edit keeps what the form does not offer, a lightning address among it`() {
        val latest = existing("""{"name":"Old","bot":true,"lud16":"group@getalby.com"}""")

        val (proposed, _) = propose(latest = latest, name = "New")

        val metadata = read(proposed.content)
        assertEquals("New", metadata.name)
        assertEquals(true, metadata.bot)
        assertEquals("group@getalby.com", metadata.lud16, "D6: an edit made here must not wipe it")
    }

    @Test
    fun `an emptied field unsays what the group had said`() {
        val latest = existing("""{"name":"Old","about":"Something regretted","nip05":"old@example.com"}""")

        val (proposed, _) = propose(latest = latest, name = "Old", about = "", nip05 = "")

        val metadata = read(proposed.content)
        assertNull(metadata.about)
        assertNull(metadata.nip05)
    }
}
