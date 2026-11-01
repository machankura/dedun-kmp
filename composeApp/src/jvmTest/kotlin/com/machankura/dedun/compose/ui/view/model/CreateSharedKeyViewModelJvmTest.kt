package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.nostr.subgroup.SubgroupWalletPolicy
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.phoenix.utils.preferences.InternalPrefs
import fr.acinq.phoenix.utils.preferences.UserPrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalParticipant
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.identity.IdentityKind
import com.machankura.dedun.compose.identity.toWalletId
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.DkgRepository
import com.machankura.dedun.compose.ui.view.state.CreateSharedKeyUIState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The one screen that starts a group's own key ceremony.
 *
 * What it must decide before it draws a button: whether the room can hold a
 * ceremony of its own at all, and whether it already has one. A Marmot room
 * cannot, and the ceremony screen offering it one anyway is how a second
 * ceremony opened beside a subgroup's -- see docs/ceremony-creation.md.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreateSharedKeyViewModelJvmTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val chatRoomId = "9a17".repeat(16)

    private val key = PrivateKey(ByteVector32("0e".repeat(32)))
    private val ada = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val cleo = "c".repeat(64)

    private fun room(
        members: List<HexKey> = listOf(ada, bob, cleo),
        mlsGroupState: String? = null,
    ) = LocalChatRoom(
        chatRoom = ChatRoom(
            id = chatRoomId,
            userPublicKey = ada,
            subject = "Checkers",
            description = null,
            mlsGroupState = mlsGroupState,
        ),
        localParticipants = members.map {
            LocalParticipant(Participant(participantPublicKey = it, chatRoomId = chatRoomId, relayHint = null), null)
        },
    )

    private fun session(id: String, stage: DkgRitualStage) = DkgSession(
        id = id,
        chatRoomId = chatRoomId,
        coordinatorPublicKey = bob,
        userPublicKey = ada,
        threshold = 2,
        participantCount = 3,
        stage = stage,
        hostPublicKey = ada,
        round1Random = "aa".repeat(32),
        round2AuxRandom = "bb".repeat(32),
    )

    /** What `proposeRitual` was asked for, one entry per call. */
    private val proposed = mutableListOf<Int>()

    private fun dkgRepository(own: DkgSession? = null) =
        object : DkgRepository by DkgRepository.NO_OP_DKG_REPOSITORY {
            override fun observeLatestSessionForChatRoom(
                chatRoomId: String,
                parentChatRoomId: String?,
            ): Flow<DkgSession?> = flowOf(own.takeIf { parentChatRoomId == null })

            override suspend fun proposeRitual(
                localChatRoom: LocalChatRoom,
                userPublicKey: HexKey,
                nostrPrivateKey: ByteArray,
                threshold: Int,
                parentChatRoomId: HexKey?,
                participantPublicKeys: Set<HexKey>?,
                subject: String?,
                walletPolicy: SubgroupWalletPolicy?,
                guardianDelay: Long?
            ): DkgSession? {
                proposed += threshold
                return session("new".padEnd(64, '0'), DkgRitualStage.COLLECTING_HOST_KEYS)
            }
        }

    private fun chatRepository(room: LocalChatRoom?) =
        object : ChatRepository by ChatRepository.NO_OP_CHAT_REPOSITORY {
            override suspend fun getChatRoomByIdentifier(id: HexKey): LocalChatRoom? =
                room.takeIf { id == chatRoomId }
        }

    private fun prefsStore(name: String) = PreferenceDataStoreFactory.createWithPath {
        temporaryFolder.newFolder().resolve("$name.preferences_pb").path.toPath()
    }

    private fun viewModel(room: LocalChatRoom?, own: DkgSession? = null) = CreateSharedKeyViewModel(
        chatRoomId = chatRoomId,
        activeUserPublicKey = ada,
        initialUIState = CreateSharedKeyUIState.Loading,
        activeIdentityStateFlow = MutableStateFlow(
            Identity.signing(
                id = key.publicKey().xOnly().toWalletId(),
                kind = IdentityKind.NostrSecret,
                nostrPrivateKey = key,
                userPrefs = UserPrefs(prefsStore("user")),
                internalPrefs = InternalPrefs(prefsStore("internal")),
                business = null,
            )
        ),
        chatRepository = chatRepository(room),
        dkgRepository = dkgRepository(own),
    )

    private suspend fun CreateSharedKeyViewModel.awaitSettled(): CreateSharedKeyUIState =
        withTimeout(5_000) {
            while (uiState == CreateSharedKeyUIState.Loading) delay(10)
            uiState
        }

    @Test
    fun `a group with no ceremony is offered one, at the majority default`(): Unit = runBlocking {
        val viewModel = viewModel(room())
        viewModel.initiate()

        val loaded = assertIs<CreateSharedKeyUIState.Loaded>(viewModel.awaitSettled())
        assertEquals(3, loaded.participantCount)
        assertEquals(2, viewModel.threshold.value)

        val opened = CompletableDeferred<String>()
        viewModel.create { opened.complete(it) }

        assertEquals("new".padEnd(64, '0'), withTimeout(5_000) { opened.await() })
        assertEquals(listOf(2), proposed)
    }

    @Test
    fun `a group with a ceremony already is handed to it`(): Unit = runBlocking {
        val running = session("run".padEnd(64, '0'), DkgRitualStage.COLLECTING_ROUND_1)
        val viewModel = viewModel(room(), own = running)
        viewModel.initiate()

        assertEquals(CreateSharedKeyUIState.Existing(running.id), viewModel.awaitSettled())
        assertEquals(emptyList(), proposed, "and nothing is proposed")
    }

    @Test
    fun `a failed ceremony is there to be replaced`(): Unit = runBlocking {
        val failed = session("bad".padEnd(64, '0'), DkgRitualStage.FAILED)
        val viewModel = viewModel(room(), own = failed)
        viewModel.initiate()

        assertIs<CreateSharedKeyUIState.Loaded>(viewModel.awaitSettled())
    }

    @Test
    fun `a Marmot room is refused, and says why`(): Unit = runBlocking {
        val viewModel = viewModel(room(mlsGroupState = "state"))
        viewModel.initiate()

        assertEquals(
            CreateSharedKeyUIState.Refused(CreateSharedKeyUIState.Refused.Reason.MARMOT_ROOM),
            viewModel.awaitSettled(),
        )

        viewModel.create { error("a refused room opens nothing") }
        assertEquals(emptyList(), proposed)
    }

    @Test
    fun `a group of one is refused`(): Unit = runBlocking {
        val viewModel = viewModel(room(members = listOf(ada)))
        viewModel.initiate()

        assertEquals(
            CreateSharedKeyUIState.Refused(CreateSharedKeyUIState.Refused.Reason.TOO_FEW_MEMBERS),
            viewModel.awaitSettled(),
        )
    }

    @Test
    fun `a room that cannot be read is an error with a way to retry`(): Unit = runBlocking {
        val viewModel = viewModel(room = null)
        viewModel.initiate()

        val error = assertIs<CreateSharedKeyUIState.Error>(viewModel.awaitSettled())
        assertEquals(CreateSharedKeyUIState.Error.Reason.ROOM_NOT_FOUND, error.reason)
        assertNull(proposed.firstOrNull())
    }
}
