package com.machankura.dedun.compose.ui.view.model

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerSync
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.phoenix.utils.preferences.InternalPrefs
import fr.acinq.phoenix.utils.preferences.UserPrefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.DkgParticipantMessage
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.FrostSigningItem
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalFrostSigningSession
import com.machankura.dedun.compose.database.model.types.DkgApprovalStep
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.identity.IdentityKind
import com.machankura.dedun.compose.identity.toWalletId
import com.machankura.dedun.compose.nostr.dkg.DkgRitualEvents
import com.machankura.dedun.compose.nostr.frost.GroupKeyStateEvent
import com.machankura.dedun.compose.nostr.subgroup.SubgroupBirthCertificateEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.DkgRepository
import com.machankura.dedun.compose.ui.composable.navigation.routes.CreateSharedKeyRoute
import com.machankura.dedun.compose.ui.composable.navigation.routes.SelectSubgroupAdminsRoute
import com.machankura.dedun.compose.ui.composable.retryRoute
import com.machankura.dedun.compose.ui.view.state.DkgRitualUIState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ceremony screen keeping hold of its ceremony.
 *
 * It lost it twice over, and the second loss is what put a "Start key ceremony"
 * button where "Review and join" had just been: coming back from an approval
 * screen ran `initiate` again, which reset the state to "no ceremony", and five
 * coroutines each copying the state to change one field could write an old
 * copy back over a newer one. See docs/ceremony-creation.md, findings F3, F4
 * and F6.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DkgRitualViewModelJvmTest {
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

    private val chatRoomId = "c3a1".repeat(16)
    private val sessionId = "5e55".repeat(16)

    private val key = PrivateKey(ByteVector32("0d".repeat(32)))
    private val ada = "a".repeat(64)
    private val bob = "b".repeat(64)

    private val localChatRoom = LocalChatRoom(
        chatRoom = ChatRoom(
            id = chatRoomId,
            userPublicKey = ada,
            subject = "Checkers",
            description = null,
            mlsGroupState = null,
        )
    )

    private fun session(stage: DkgRitualStage = DkgRitualStage.COLLECTING_HOST_KEYS) = DkgSession(
        id = sessionId,
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

    private fun hostKey(from: HexKey) = DkgParticipantMessage(
        sessionId = sessionId,
        participantPublicKey = from,
        kind = DkgRitualEvents.HOST_KEY,
        payload = "02".repeat(33),
    )

    private val sessionRow = MutableStateFlow<DkgSession?>(session())
    private val messages = MutableStateFlow<List<DkgParticipantMessage>>(emptyList())
    private val signingSessions = MutableStateFlow<List<LocalFrostSigningSession>>(emptyList())

    /** How many times the screen started watching its ceremony. */
    private var sessionSubscriptions = 0

    /** Held open so a test can see what happens before an approval returns. */
    private var approvalGate: CompletableDeferred<Unit>? = null
    private val approved = mutableListOf<DkgApprovalStep>()

    private val dkgRepository = object : DkgRepository by DkgRepository.NO_OP_DKG_REPOSITORY {
        override fun observeSessionById(sessionId: String): Flow<DkgSession?> =
            sessionRow.onStart { sessionSubscriptions++ }

        override fun observeMessages(sessionId: String): Flow<List<DkgParticipantMessage>> = messages

        override fun observeSigningSessions(chatRoomId: String): Flow<List<LocalFrostSigningSession>> =
            signingSessions

        // A deliberately slow answer, so a read-modify-write around it would have
        // the widest possible window to lose somebody else's write in.
        override suspend fun pendingApproval(session: DkgSession): DkgApprovalStep? {
            delay(5)
            return if (messages.value.size >= session.participantCount) DkgApprovalStep.ROUND_1 else null
        }

        override suspend fun approve(
            localChatRoom: LocalChatRoom,
            sessionId: String,
            step: DkgApprovalStep,
            nostrPrivateKey: ByteArray
        ) {
            approvalGate?.await()
            approved += step
        }
    }

    private val chatRepository = object : ChatRepository by ChatRepository.NO_OP_CHAT_REPOSITORY {
        override suspend fun getChatRoomByIdentifier(id: HexKey): LocalChatRoom? =
            localChatRoom.takeIf { id == chatRoomId }
    }

    private fun prefsStore(name: String) = PreferenceDataStoreFactory.createWithPath {
        temporaryFolder.newFolder().resolve("$name.preferences_pb").path.toPath()
    }

    private fun viewModel() = DkgRitualViewModel(
        chatRoomId = chatRoomId,
        activeUserPublicKey = ada,
        dkgSessionId = sessionId,
        initialDkgRitualUIState = DkgRitualUIState.Loading,
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
        chatRepository = chatRepository,
        dkgRepository = dkgRepository,
    )

    private suspend fun DkgRitualViewModel.awaitLoaded(
        predicate: (DkgRitualUIState.Loaded) -> Boolean = { true }
    ): DkgRitualUIState.Loaded = withTimeout(5_000) {
        while (true) {
            val state = dkgRitualUIState
            if (state is DkgRitualUIState.Loaded && predicate(state)) return@withTimeout state
            delay(10)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    @Test
    fun `coming back to the screen keeps the ceremony it was showing`(): Unit = runBlocking {
        val viewModel = viewModel()

        viewModel.initiate()
        assertEquals(sessionId, viewModel.awaitLoaded().session?.id)
        val watchers = sessionSubscriptions

        // What the screen does each time it comes back into view.
        viewModel.initiate()
        viewModel.initiate()

        repeat(20) {
            val state = assertIs<DkgRitualUIState.Loaded>(viewModel.dkgRitualUIState)
            assertEquals(sessionId, state.session?.id, "the ceremony is never dropped from the screen")
            delay(5)
        }
        assertEquals(watchers, sessionSubscriptions, "and its watchers are started once, not once per visit")
    }

    @Test
    fun `the first state the screen draws already has the ceremony on it`(): Unit = runBlocking {
        val viewModel = viewModel()

        // Recorded as fast as it changes. The old path set a ceremony-less
        // `Loaded` first and filled the session in afterwards, which is the moment
        // a start button could be drawn.
        val seen = mutableListOf<DkgRitualUIState>()
        viewModel.initiate()
        withTimeout(5_000) {
            while (seen.none { it is DkgRitualUIState.Loaded }) {
                viewModel.dkgRitualUIState.let { if (seen.lastOrNull() != it) seen += it }
                delay(1)
            }
        }

        seen.filterIsInstance<DkgRitualUIState.Loaded>().forEach {
            assertEquals(sessionId, it.session?.id)
        }
    }

    @Test
    fun `parts that change together all land`(): Unit = runBlocking {
        val viewModel = viewModel()
        viewModel.initiate()
        viewModel.awaitLoaded()

        // The session row and the messages moving at once, from two directions,
        // each while the other's write is waiting on `pendingApproval`.
        listOf(
            async(Dispatchers.IO) {
                listOf(ada, bob, "c".repeat(64)).forEach { from ->
                    messages.value = messages.value + hostKey(from)
                    delay(2)
                }
            },
            async(Dispatchers.IO) {
                repeat(10) {
                    sessionRow.value = session().copy(failureReason = "tick $it")
                    delay(1)
                }
                sessionRow.value = session(DkgRitualStage.COLLECTING_ROUND_1)
            },
        ).awaitAll()

        val settled = viewModel.awaitLoaded {
            it.session?.stage == DkgRitualStage.COLLECTING_ROUND_1 &&
                it.hostKeyParticipants.size == 3 &&
                it.pendingApproval == DkgApprovalStep.ROUND_1
        }
        assertEquals(sessionId, settled.session?.id)
        assertEquals(setOf(ada, bob, "c".repeat(64)), settled.hostKeyParticipants)
    }

    @Test
    fun `a ceremony this device has no row for is an error, not an offer`(): Unit = runBlocking {
        sessionRow.value = null
        val viewModel = viewModel()
        viewModel.initiate()

        withTimeout(5_000) {
            while (viewModel.dkgRitualUIState !is DkgRitualUIState.Error) delay(10)
        }
    }

    @Test
    fun `a failed ceremony is tried again where its kind is started`() {
        val parent = "9a7e".repeat(16)

        val group = retryRoute(ada, chatRoomId, session(DkgRitualStage.FAILED))
        assertEquals(CreateSharedKeyRoute(activeUserPublicKey = ada, chatRoomId = chatRoomId), group)

        val subgroup = retryRoute(
            ada,
            chatRoomId,
            session(DkgRitualStage.FAILED).copy(parentChatRoomId = parent)
        )
        assertEquals(
            SelectSubgroupAdminsRoute(activeUserPublicKey = ada, parentChatRoomId = parent, retrying = sessionId),
            subgroup,
            "a subgroup's goes back to the picker, filled in from the one that failed",
        )
    }

    @Test
    fun `an approval screen closes after the approval has gone out`(): Unit = runBlocking {
        val viewModel = viewModel()
        viewModel.initiate()
        viewModel.awaitLoaded()

        val gate = CompletableDeferred<Unit>().also { approvalGate = it }
        val closed = CompletableDeferred<Unit>()

        viewModel.approve(DkgApprovalStep.HOST_KEY, onApproved = { closed.complete(Unit) })

        delay(50)
        assertFalse(closed.isCompleted, "not while the approval is still being sent")
        assertTrue(viewModel.isActionPending.value, "and the button says it is working")

        gate.complete(Unit)
        withTimeout(5_000) { closed.await() }

        assertEquals(listOf(DkgApprovalStep.HOST_KEY), approved)
        assertFalse(viewModel.isActionPending.value)
    }

    /** A signing session in this room over one event of [kind]. */
    private fun signing(
        id: String,
        kind: Int,
        tags: Array<Array<String>>,
        content: String,
        stage: FrostSigningStage = FrostSigningStage.COMPLETE,
    ): LocalFrostSigningSession {
        val event = NostrSignerSync(KeyPair()).sign<Event>(
            createdAt = 1_700_000_000L,
            kind = kind,
            tags = tags,
            content = content,
        )
        return LocalFrostSigningSession(
            session = FrostSigningSession(
                id = id,
                chatRoomId = chatRoomId,
                coordinatorPublicKey = bob,
                userPublicKey = ada,
                dkgSessionId = sessionId,
                threshold = 2,
                participantCount = 3,
                signerId = 1,
                stage = stage,
            ),
            unorderedItems = listOf(
                FrostSigningItem(
                    sessionId = id,
                    itemIndex = 0,
                    unsignedEventJson = event.toJson(),
                    eventId = event.id,
                    nonceRandom = "cc".repeat(32),
                )
            ),
        )
    }

    @Test
    fun `a parent's second subgroup is not shown its first one's signing sessions`(): Unit = runBlocking {
        // The ceremony is held in the parent's room, so this room is the parent's
        // -- and it already holds the first subgroup's finished certificate and
        // key state, newest first, as the store returns them.
        val firstKey = "02" + "1f".repeat(32)
        val secondKey = "02" + "2f".repeat(32)
        sessionRow.value = session(DkgRitualStage.COMPLETE).copy(
            parentChatRoomId = chatRoomId,
            thresholdPublicKey = secondKey,
        )
        signingSessions.value = listOf(
            signing(
                id = "first-key-state",
                kind = GroupKeyStateEvent.KIND,
                tags = GroupKeyStateEvent.assembleTags(chatRoomId = "f1".repeat(32), dkgSessionId = "f1"),
                content = firstKey,
            ),
            signing(
                id = "first-certificate",
                kind = SubgroupBirthCertificateEvent.KIND,
                tags = SubgroupBirthCertificateEvent.assembleTags(
                    subgroupChatRoomId = "f1".repeat(32),
                    parentChatRoomId = chatRoomId,
                    thresholdPublicKey = firstKey,
                    adminPublicKeys = listOf(ada, bob),
                    name = "First",
                ),
                content = "f1".repeat(32),
            ),
        )

        val viewModel = viewModel()
        viewModel.initiate()

        val before = viewModel.awaitLoaded()
        assertNull(before.certificateSession, "the first subgroup's certificate is not this one's")
        assertNull(before.keyStateSession, "nor is its key state")

        // Its own, once asked for, is the one shown.
        signingSessions.value = listOf(
            signing(
                id = "second-certificate",
                kind = SubgroupBirthCertificateEvent.KIND,
                tags = SubgroupBirthCertificateEvent.assembleTags(
                    subgroupChatRoomId = "f2".repeat(32),
                    parentChatRoomId = chatRoomId,
                    thresholdPublicKey = secondKey,
                    adminPublicKeys = listOf(ada, bob),
                    name = "Second",
                ),
                content = "f2".repeat(32),
                stage = FrostSigningStage.COLLECTING_NONCES,
            ),
        ) + signingSessions.value

        assertEquals(
            "second-certificate",
            viewModel.awaitLoaded { it.certificateSession != null }.certificateSession?.id,
        )
    }
}
