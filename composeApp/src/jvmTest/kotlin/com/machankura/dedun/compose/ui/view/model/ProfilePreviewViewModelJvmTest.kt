package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.nostr.MarmotProfile
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import com.machankura.dedun.compose.database.GENESIS_AT
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.MarmotKeyPackage
import com.machankura.dedun.compose.database.model.NegentropySynchronizeRequest
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.database.model.SynchronizeNostrEventRequest
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalParticipant
import com.machankura.dedun.compose.nostr.DirectMessagePeerSync
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.view.state.ProfilePreviewUIState
import com.machankura.dedun.compose.ui.view.state.ProfilePreviewUIState.Readiness
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * What the preview shows while the relays are asked, when it stops waiting, and
 * whether the button it ends on can be pressed.
 *
 * The repository fakes record every ask and hold the profile row and the key package
 * in `MutableStateFlow`s, so the test can play them arriving in either order, after
 * the timeout, or never. The view model launches on `Dispatchers.IO`, as the others
 * do, so the timeout is real time cut to a few hundred milliseconds through its
 * constructor, and the assertions poll.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfilePreviewViewModelJvmTest {

    private val me = "a".repeat(64)
    private val alice = "b".repeat(64)

    /** Records the asks; the profile flow is the row the device holds. */
    private class Recorder(
        val profile: MutableStateFlow<Profile?> = MutableStateFlow(null),
        private val refuse: Boolean = false,
    ) : NostrRepository by NostrRepository.NO_OP_NOSTR_REPOSITORY {
        val effects = mutableListOf<String>()

        override suspend fun observeProfileWithPublicKey(publicKey: HexKey): Flow<Profile?> = profile

        override suspend fun queueSynchronizeNostrEvent(synchronizeNostrEventRequests: List<SynchronizeNostrEventRequest>) {
            if (refuse) throw IllegalStateException("the queue is closed")
            effects += "req:" + synchronizeNostrEventRequests.map { it.purpose }.distinct().joinToString()
        }

        override suspend fun queueNegentropySynchronizeRequest(negentropySynchronizeRequests: List<NegentropySynchronizeRequest>) {
            if (refuse) throw IllegalStateException("the queue is closed")
            effects += "neg:" + negentropySynchronizeRequests.map { it.purpose }.distinct().joinToString()
        }
    }

    /** The key package the device holds for the peer, and the room it already has with them. */
    private class Rooms(
        val keyPackage: MutableStateFlow<MarmotKeyPackage?> = MutableStateFlow(null),
        private val existingRoom: LocalChatRoom? = null,
    ) : ChatRepository by ChatRepository.NO_OP_CHAT_REPOSITORY {
        override suspend fun observeMarmotKeyPackageForPublicKey(publicKey: HexKey, roomProfile: MarmotProfile): Flow<MarmotKeyPackage?> = keyPackage
        override suspend fun findDirectMessageRoom(userPublicKey: HexKey, peerPublicKey: HexKey): LocalChatRoom? = existingRoom
    }

    private val profileAsk = "req:${DirectMessagePeerSync.PROFILE_PURPOSE}"
    private val chatAsk = "neg:${DirectMessagePeerSync.PURPOSE}"
    private val bothAsks = listOf(profileAsk, chatAsk)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(recorder: Recorder, rooms: Rooms = Rooms()) = ProfilePreviewViewModel(
        activeUserPublicKey = me,
        profilePublicKey = alice,
        initialProfilePreviewUIState = ProfilePreviewUIState.Loading,
        nostrRepository = recorder,
        chatRepository = rooms,
        relayLookupTimeout = 300.milliseconds,
    )

    // --- finding the person ---------------------------------------------------------------

    @Test
    fun `initiating asks both sets of relays once, and waits`() = runBlocking {
        val recorder = Recorder()
        val viewModel = viewModel(recorder)

        viewModel.initiate()
        await { recorder.effects.size == 2 }

        assertEquals(bothAsks, recorder.effects)
        assertEquals(ProfilePreviewUIState.Loading, viewModel.profilePreviewUIState)

        // A recomposition calling it again opens no second collect and asks nothing more.
        viewModel.initiate()
        delay(50)
        assertEquals(bothAsks, recorder.effects)
    }

    @Test
    fun `a placeholder row is not a person`() = runBlocking {
        val recorder = Recorder(profile = MutableStateFlow(placeholder(alice)))
        val viewModel = viewModel(recorder)

        viewModel.initiate()
        await { recorder.effects.size == 2 }
        delay(50)

        assertEquals(ProfilePreviewUIState.Loading, viewModel.profilePreviewUIState)
    }

    /** Cached first: a person already known is on screen before any relay answers. */
    @Test
    fun `a row already resolved loads at once, and the relays are still asked`() = runBlocking {
        val recorder = Recorder(profile = MutableStateFlow(resolved(alice)))
        val viewModel = viewModel(recorder)

        viewModel.initiate()
        await { viewModel.profilePreviewUIState is ProfilePreviewUIState.Loaded }

        assertEquals(loaded(Readiness.Checking), viewModel.profilePreviewUIState)
        await { recorder.effects.size == 2 }
        assertEquals(bothAsks, recorder.effects)
    }

    @Test
    fun `nothing arriving is not found, and a late kind 0 still loads`() = runBlocking {
        val recorder = Recorder()
        val viewModel = viewModel(recorder)

        viewModel.initiate()
        await { viewModel.profilePreviewUIState == ProfilePreviewUIState.NotFound }

        recorder.profile.value = resolved(alice)
        await { viewModel.profilePreviewUIState is ProfilePreviewUIState.Loaded }
    }

    @Test
    fun `try again from not found asks both sets of relays again`() = runBlocking {
        val recorder = Recorder()
        val viewModel = viewModel(recorder)

        viewModel.initiate()
        await { viewModel.profilePreviewUIState == ProfilePreviewUIState.NotFound }

        viewModel.retry()
        assertEquals(ProfilePreviewUIState.Loading, viewModel.profilePreviewUIState)
        await { recorder.effects.size == 4 }
        assertEquals(bothAsks + bothAsks, recorder.effects)

        // And the clock was restarted with it.
        await { viewModel.profilePreviewUIState == ProfilePreviewUIState.NotFound }
    }

    @Test
    fun `a queue that refuses is an error, and try again asks again`() = runBlocking {
        val recorder = Recorder(refuse = true)
        val viewModel = viewModel(recorder)

        viewModel.initiate()
        await { viewModel.profilePreviewUIState == ProfilePreviewUIState.Error }

        // The timeout was cancelled with it: an error does not later become "not found".
        delay(400)
        assertEquals(ProfilePreviewUIState.Error, viewModel.profilePreviewUIState)

        viewModel.retry()
        await { viewModel.profilePreviewUIState == ProfilePreviewUIState.Error }
    }

    // --- whether the button can be pressed -----------------------------------------------

    /**
     * A key package is meant to be used once, and the relays need not hold a fresh one
     * for a person you already have a room with: without this row, someone you talk to
     * every day would read "hasn't set up messaging".
     */
    @Test
    fun `a room that exists opens, key package or not`() = runBlocking<Unit> {
        val recorder = Recorder(profile = MutableStateFlow(resolved(alice)))
        val viewModel = viewModel(recorder, Rooms(existingRoom = roomWith(alice)))

        viewModel.initiate()
        await { viewModel.profilePreviewUIState is ProfilePreviewUIState.Loaded }

        assertEquals(loaded(Readiness.ExistingChat(chatRoomId = "room", relayHint = "wss://hint.example")), viewModel.profilePreviewUIState)

        // Nothing left to wait for, so the timeout has nothing to say.
        delay(400)
        assertIs<Readiness.ExistingChat>(assertIs<ProfilePreviewUIState.Loaded>(viewModel.profilePreviewUIState).readiness)
    }

    @Test
    fun `the profile first, then the key package, and the clock stops`() = runBlocking {
        val recorder = Recorder()
        val rooms = Rooms()
        val viewModel = viewModel(recorder, rooms)

        viewModel.initiate()
        recorder.profile.value = resolved(alice)
        await { viewModel.profilePreviewUIState == loaded(Readiness.Checking) }

        rooms.keyPackage.value = keyPackage(alice)
        await { viewModel.profilePreviewUIState == loaded(Readiness.CanStartChat) }

        delay(400)
        assertEquals(loaded(Readiness.CanStartChat), viewModel.profilePreviewUIState)
    }

    @Test
    fun `the key package first, then the profile, is ready at once`() = runBlocking {
        val recorder = Recorder()
        val rooms = Rooms(keyPackage = MutableStateFlow(keyPackage(alice)))
        val viewModel = viewModel(recorder, rooms)

        viewModel.initiate()
        delay(50)
        assertEquals(ProfilePreviewUIState.Loading, viewModel.profilePreviewUIState)

        recorder.profile.value = resolved(alice)
        await { viewModel.profilePreviewUIState == loaded(Readiness.CanStartChat) }
    }

    @Test
    fun `a person with no key package is not yet on Dedun after the timeout, and a late one still counts`() = runBlocking {
        val recorder = Recorder(profile = MutableStateFlow(resolved(alice)))
        val rooms = Rooms()
        val viewModel = viewModel(recorder, rooms)

        viewModel.initiate()
        await { viewModel.profilePreviewUIState == loaded(Readiness.Checking) }
        await { viewModel.profilePreviewUIState == loaded(Readiness.NotYetOnDedun) }

        rooms.keyPackage.value = keyPackage(alice)
        await { viewModel.profilePreviewUIState == loaded(Readiness.CanStartChat) }
    }

    /** The person is found; it is the key package being asked for again, and the DM relays alone are asked. */
    @Test
    fun `try again with a person on screen asks the DM relays alone and keeps the person`() = runBlocking {
        val recorder = Recorder(profile = MutableStateFlow(resolved(alice)))
        val viewModel = viewModel(recorder, Rooms())

        viewModel.initiate()
        await { viewModel.profilePreviewUIState == loaded(Readiness.NotYetOnDedun) }
        assertEquals(bothAsks, recorder.effects)

        viewModel.retry()
        assertEquals(loaded(Readiness.Checking), viewModel.profilePreviewUIState)
        await { recorder.effects.size == 3 }
        assertEquals(bothAsks + chatAsk, recorder.effects)

        await { viewModel.profilePreviewUIState == loaded(Readiness.NotYetOnDedun) }
    }

    // --- fixtures -----------------------------------------------------------------------

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(10)
        }
    }

    private fun loaded(readiness: Readiness) = ProfilePreviewUIState.Loaded(profile = resolved(alice), readiness = readiness)

    /** What every mint site writes: the "LOADING..." name, stamped `GENESIS_AT`. */
    private fun placeholder(publicKey: HexKey) = Profile(
        publicKey = publicKey,
        displayName = "LOADING...",
        nostrEventId = "placeholder",
        createdAt = GENESIS_AT,
    )

    private val at = Instant.fromEpochSeconds(1_700_000_000)

    private fun resolved(publicKey: HexKey) = Profile(
        publicKey = publicKey,
        displayName = "Alice",
        nostrEventId = "kind0",
        createdAt = at,
        updatedAt = at,
        savedAt = at,
    )

    private fun keyPackage(publicKey: HexKey) = MarmotKeyPackage(
        id = "keypackage",
        publicKey = publicKey,
        tlsEncodedMarmotKeyPackage = ByteArray(0),
    )

    private fun roomWith(peer: HexKey) = LocalChatRoom(
        chatRoom = ChatRoom(
            id = "room",
            userPublicKey = me,
            subject = null,
            description = null,
            mlsGroupState = "state",
        ),
        localParticipants = listOf(me, peer).map { publicKey ->
            LocalParticipant(
                participant = Participant(
                    participantPublicKey = publicKey,
                    chatRoomId = "room",
                    relayHint = "wss://hint.example",
                ),
                profile = null,
            )
        },
    )
}
