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
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The one screen that starts a group's key ceremony in a NIP-17 room.
 *
 * What it must decide before it draws a button: whether the room can hold a
 * ceremony of its own at all, and what the group is to be called. A Marmot room
 * cannot hold one, and the ceremony screen offering it one anyway is how a second
 * ceremony opened beside a subgroup's -- see docs/ceremony-creation.md. A NIP-17
 * room that already has a group can hold another, which has to be named:
 * docs/nip17-multiple-ceremonies.md.
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

    private fun session(id: String, stage: DkgRitualStage, subject: String? = null, threshold: Int = 2) = DkgSession(
        id = id,
        chatRoomId = chatRoomId,
        coordinatorPublicKey = bob,
        userPublicKey = ada,
        threshold = threshold,
        participantCount = 3,
        stage = stage,
        subject = subject,
        hostPublicKey = ada,
        round1Random = "aa".repeat(32),
        round2AuxRandom = "bb".repeat(32),
    )

    /** What `proposeRitual` was asked for, one entry per call. */
    private val proposed = mutableListOf<Int>()

    /** And under what name. */
    private val subjects = mutableListOf<String?>()

    /** What `proposeRitual` hands back: a new ceremony unless a test says otherwise. */
    private var answer: DkgSession = session("new".padEnd(64, '0'), DkgRitualStage.COLLECTING_HOST_KEYS)

    private fun dkgRepository(groups: List<DkgSession> = emptyList(), byId: List<DkgSession> = emptyList()) =
        object : DkgRepository by DkgRepository.NO_OP_DKG_REPOSITORY {
            override fun observeOwnSessionsForChatRoom(chatRoomId: String): Flow<List<DkgSession>> =
                flowOf(groups)

            override fun observeSessionById(sessionId: String): Flow<DkgSession?> =
                flowOf(byId.firstOrNull { it.id == sessionId })

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
                subjects += subject
                return answer
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

    private fun viewModel(
        room: LocalChatRoom?,
        groups: List<DkgSession> = emptyList(),
        retrying: DkgSession? = null,
    ) = CreateSharedKeyViewModel(
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
        dkgRepository = dkgRepository(groups, listOfNotNull(retrying)),
        retrying = retrying?.id,
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
        assertEquals(listOf<String?>("Checkers"), subjects, "the room's first group is named for the room")
    }

    /**
     * The same people may make another group. It is not handed over to the first,
     * as it was, and it has to be named: the room keeps its first group's name.
     */
    @Test
    fun `a room that already has a group is offered another, under a name of its own`(): Unit = runBlocking {
        val made = session("one".padEnd(64, '0'), DkgRitualStage.COMPLETE, subject = "Checkers")
        val viewModel = viewModel(room(), groups = listOf(made))
        viewModel.initiate()

        val loaded = assertIs<CreateSharedKeyUIState.Loaded>(viewModel.awaitSettled())
        assertEquals(listOf(made), loaded.groups)
        assertTrue(loaded.needsName)
        assertEquals("", viewModel.name.value, "not the room's name, which is the first group's")
        assertFalse(viewModel.canCreate(), "nothing opens without a name")

        viewModel.create { error("an unnamed second group opens nothing") }
        delay(50)
        assertEquals(emptyList(), proposed)

        viewModel.setName("  Chess ")
        assertTrue(viewModel.canCreate())

        val opened = CompletableDeferred<String>()
        viewModel.create { opened.complete(it) }
        assertEquals(answer.id, withTimeout(5_000) { opened.await() })
        assertEquals(listOf<String?>("Chess"), subjects)
    }

    /** A name already running is the same ask, and the screen hands over to it. */
    @Test
    fun `asking under a running group's name opens that group`(): Unit = runBlocking {
        val running = session("run".padEnd(64, '0'), DkgRitualStage.COLLECTING_ROUND_1, subject = "Chess")
        answer = running
        val viewModel = viewModel(room(), groups = listOf(running))
        viewModel.initiate()
        viewModel.awaitSettled()
        viewModel.setName("Chess")

        val opened = CompletableDeferred<String>()
        viewModel.create { opened.complete(it) }
        assertEquals(running.id, withTimeout(5_000) { opened.await() })
    }

    @Test
    fun `a failed ceremony is there to be replaced`(): Unit = runBlocking {
        // The repository leaves failed ceremonies out of a room's groups.
        val viewModel = viewModel(room(), groups = emptyList())
        viewModel.initiate()

        assertIs<CreateSharedKeyUIState.Loaded>(viewModel.awaitSettled())
        assertEquals("Checkers", viewModel.name.value)
    }

    /**
     * A retry of a room's second group comes back as that group, not as the room's
     * first: the room's name is the first group's.
     */
    @Test
    fun `a retry starts from the name and quorum of the ceremony that failed`(): Unit = runBlocking {
        val made = session("one".padEnd(64, '0'), DkgRitualStage.COMPLETE, subject = "Checkers")
        val failed = session("bad".padEnd(64, '0'), DkgRitualStage.FAILED, subject = "Chess", threshold = 3)
        val viewModel = viewModel(room(), groups = listOf(made), retrying = failed)
        viewModel.initiate()

        assertIs<CreateSharedKeyUIState.Loaded>(viewModel.awaitSettled())
        assertEquals("Chess", viewModel.name.value)
        assertEquals(3, viewModel.threshold.value)
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
