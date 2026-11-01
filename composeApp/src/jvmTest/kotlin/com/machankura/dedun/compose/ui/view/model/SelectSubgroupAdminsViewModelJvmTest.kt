package com.machankura.dedun.compose.ui.view.model

import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull
import com.machankura.dedun.compose.managers.GuardianDerivation
import com.machankura.dedun.compose.nostr.subgroup.WalletPolicyReading
import com.machankura.dedun.compose.nostr.subgroup.SubgroupWalletPolicy
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.MarmotKeyPackage
import com.machankura.dedun.compose.database.model.Participant
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalParticipant
import com.machankura.dedun.compose.database.model.types.DkgRitualStage
import com.machankura.dedun.compose.nostr.MarmotProfile
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.DkgRepository
import com.machankura.dedun.compose.ui.view.state.SelectSubgroupAdminsUIState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Trying a failed subgroup ceremony again starts from what it was.
 *
 * The ceremony screen used to retry any failed ceremony by opening a group's own
 * -- in a parent's room, a whole-room ceremony that was never the subgroup at
 * all. It now sends a subgroup's back to this picker, and the picker has to
 * start from the name, the admins and the quorum that failed. See
 * docs/ceremony-creation.md, finding F7.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SelectSubgroupAdminsViewModelJvmTest {
    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val parentChatRoomId = "9a7e".repeat(16)
    private val failedId = "fa11".repeat(16)

    private val ada = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val cleo = "c".repeat(64)

    /** Was an admin of the failed ceremony, and has since left the parent. */
    private val dora = "d".repeat(64)

    private val parent = LocalChatRoom(
        chatRoom = ChatRoom(
            id = parentChatRoomId,
            userPublicKey = ada,
            subject = "#Checkers",
            description = null,
            mlsGroupState = "state",
        ),
        localParticipants = listOf(ada, bob, cleo).map {
            LocalParticipant(Participant(participantPublicKey = it, chatRoomId = parentChatRoomId, relayHint = null), null)
        },
    )

    private fun failed(
        parentChatRoomId: String? = this.parentChatRoomId,
        walletPolicy: SubgroupWalletPolicy? = null,
        guardianDelay: Long? = null,
    ) = DkgSession(
        id = failedId,
        chatRoomId = this.parentChatRoomId,
        coordinatorPublicKey = ada,
        userPublicKey = ada,
        threshold = 3,
        participantCount = 4,
        participantPublicKeys = DkgSession.formatParticipants(setOf(ada, bob, cleo, dora)),
        subject = "Tapscript",
        stage = DkgRitualStage.FAILED,
        hostPublicKey = ada,
        round1Random = "aa".repeat(32),
        round2AuxRandom = "bb".repeat(32),
        parentChatRoomId = parentChatRoomId,
        walletPolicy = walletPolicy?.tagValue,
        guardianDelay = guardianDelay,
    )

    private fun chatRepository(
        parentPolicy: WalletPolicyReading? = null,
        parentGuardians: List<GuardianDerivation.Guardian>? = emptyList(),
    ) = object : ChatRepository by ChatRepository.NO_OP_CHAT_REPOSITORY {
        override suspend fun getChatRoomByIdentifier(id: HexKey): LocalChatRoom? =
            parent.takeIf { id == parentChatRoomId }

        override suspend fun walletPolicyOf(chatRoomId: String): WalletPolicyReading? =
            parentPolicy.takeIf { chatRoomId == parentChatRoomId }

        override suspend fun guardiansOf(chatRoomId: String): List<GuardianDerivation.Guardian>? =
            parentGuardians.takeIf { chatRoomId == parentChatRoomId }

        // Never answers, so the lookup is still out while the test looks: what is
        // under test is what the form starts from, not who has a key package.
        override suspend fun observeMarmotKeyPackageForPublicKey(
            publicKey: HexKey,
            roomProfile: MarmotProfile,
        ): Flow<MarmotKeyPackage?> = flow { awaitCancellation() }
    }

    private fun viewModel(
        session: DkgSession?,
        retrying: String? = failedId,
        chatRepository: ChatRepository = chatRepository(),
    ) = SelectSubgroupAdminsViewModel(
        activeUserPublicKey = ada,
        parentChatRoomId = parentChatRoomId,
        retrying = retrying,
        initialUIState = SelectSubgroupAdminsUIState.Loading,
        activeIdentityStateFlow = MutableStateFlow(null),
        chatRepository = chatRepository,
        dkgRepository = object : DkgRepository by DkgRepository.NO_OP_DKG_REPOSITORY {
            override fun observeSessionById(sessionId: String): Flow<DkgSession?> =
                flowOf(session?.takeIf { it.id == sessionId })
        },
    )

    private suspend fun SelectSubgroupAdminsViewModel.awaitLoaded() = withTimeout(5_000) {
        while (uiState !is SelectSubgroupAdminsUIState.Loaded) delay(10)
        delay(50)
    }

    @Test
    fun `a retry starts from the failed ceremony's name, admins and quorum`(): Unit = runBlocking {
        val viewModel = viewModel(failed())
        viewModel.initiate()
        viewModel.awaitLoaded()

        assertEquals("Tapscript", viewModel.name.value)
        assertEquals(
            setOf(bob, cleo),
            viewModel.selectedPublicKeys.toSet(),
            "everyone it was with who is still in the parent, the coordinator not among them",
        )
        assertEquals(3, viewModel.threshold.value)
    }

    @Test
    fun `a member's edits survive the screen coming back into view`(): Unit = runBlocking {
        val viewModel = viewModel(failed())
        viewModel.initiate()
        viewModel.awaitLoaded()

        viewModel.setName("Tapscript II")
        viewModel.initiate()
        viewModel.awaitLoaded()

        assertEquals("Tapscript II", viewModel.name.value)
    }

    @Test
    fun `a ceremony that was not this parent's subgroup fills nothing in`(): Unit = runBlocking {
        val viewModel = viewModel(failed(parentChatRoomId = null))
        viewModel.initiate()
        viewModel.awaitLoaded()

        assertEquals("", viewModel.name.value)
        assertEquals(emptySet(), viewModel.selectedPublicKeys.toSet())
    }

    private val guarded = WalletPolicyReading.Decided(SubgroupWalletPolicy.GUARDED)

    /** A guarded parent's view model, loaded, with a name typed so a refusal is about the policy. */
    private suspend fun underGuardedParent(session: DkgSession? = null) =
        viewModel(session, chatRepository = chatRepository(parentPolicy = guarded)).apply {
            initiate()
            awaitLoaded()
            setName("Tapscript")
        }

    @Test
    fun `a subgroup is guarded unless the coordinator says otherwise`(): Unit = runBlocking {
        val viewModel = viewModel(null, retrying = null)
        viewModel.initiate()
        viewModel.awaitLoaded()

        assertEquals(SubgroupWalletPolicy.GUARDED, viewModel.walletPolicy.value)

        viewModel.setWalletPolicy(SubgroupWalletPolicy.UNGUARDED)
        assertEquals(SubgroupWalletPolicy.UNGUARDED, viewModel.walletPolicy.value, "a root group may make either")
    }

    @Test
    fun `a guarded parent's subgroup is guarded, and stays so`(): Unit = runBlocking {
        val viewModel = underGuardedParent()

        assertEquals(SubgroupWalletPolicy.GUARDED, viewModel.walletPolicy.value)
        viewModel.setWalletPolicy(SubgroupWalletPolicy.UNGUARDED)
        assertEquals(SubgroupWalletPolicy.GUARDED, viewModel.walletPolicy.value, "D4: the choice is not the coordinator's")
        assertNull(viewModel.refusal()?.takeIf { "guarded" in it })
    }

    @Test
    fun `a guarded parent refuses an unguarded subgroup that got past the screen`(): Unit = runBlocking {
        val viewModel = underGuardedParent()

        // The state forced, as a bug or a skipped screen would leave it.
        viewModel.walletPolicy.value = SubgroupWalletPolicy.UNGUARDED

        assertEquals("This group is guarded, so its subgroups are too.", viewModel.refusal())
    }

    @Test
    fun `a retry of an unguarded ceremony under a guarded parent comes back guarded`(): Unit = runBlocking {
        val viewModel = underGuardedParent(failed(walletPolicy = SubgroupWalletPolicy.UNGUARDED))

        assertEquals("Tapscript", viewModel.name.value)
        assertEquals(SubgroupWalletPolicy.GUARDED, viewModel.walletPolicy.value)
    }

    @Test
    fun `a retry starts from the failed ceremony's wallet policy`(): Unit = runBlocking {
        val viewModel = viewModel(failed(walletPolicy = SubgroupWalletPolicy.UNGUARDED))
        viewModel.initiate()
        viewModel.awaitLoaded()

        assertEquals(SubgroupWalletPolicy.UNGUARDED, viewModel.walletPolicy.value)
    }

    @Test
    fun `an unguarded or undecided parent may make either`(): Unit = runBlocking {
        listOf(WalletPolicyReading.Decided(SubgroupWalletPolicy.UNGUARDED), WalletPolicyReading.Undecided).forEach { policy ->
            val viewModel = viewModel(null, retrying = null, chatRepository = chatRepository(parentPolicy = policy))
            viewModel.initiate()
            viewModel.awaitLoaded()

            viewModel.setWalletPolicy(SubgroupWalletPolicy.UNGUARDED)
            assertEquals(SubgroupWalletPolicy.UNGUARDED, viewModel.walletPolicy.value, "under $policy")
            viewModel.setWalletPolicy(SubgroupWalletPolicy.GUARDED)
            assertEquals(SubgroupWalletPolicy.GUARDED, viewModel.walletPolicy.value, "under $policy")
        }
    }

    @Test
    fun `a parent whose guardian keys do not read cannot make a guarded subgroup`(): Unit = runBlocking {
        val viewModel = viewModel(null, retrying = null, chatRepository = chatRepository(parentGuardians = null))
        viewModel.initiate()
        viewModel.awaitLoaded()
        viewModel.setName("Tapscript")

        assertEquals(SubgroupWalletPolicy.UNGUARDED, viewModel.walletPolicy.value)
        viewModel.setWalletPolicy(SubgroupWalletPolicy.GUARDED)
        assertEquals(SubgroupWalletPolicy.UNGUARDED, viewModel.walletPolicy.value)

        viewModel.walletPolicy.value = SubgroupWalletPolicy.GUARDED
        assertEquals(
            "This group's own guardian keys can't be read, so it can't guard a subgroup.",
            viewModel.refusal(),
            "refused here, not after a ceremony",
        )
    }

    // ---- the guardian delay: docs/guardian-delay.md, Phase 5 ---------------

    private suspend fun loadedWithName(session: DkgSession? = null) = viewModel(session).apply {
        initiate()
        awaitLoaded()
        setName("Tapscript")
    }

    @Test
    fun `a guarded subgroup's delay starts at the default`(): Unit = runBlocking {
        val viewModel = loadedWithName()

        assertEquals(12L, viewModel.guardianDelay())
        assertFalse(viewModel.isCustomDelay.value)
        assertNull(viewModel.guardianDelayProblem())
    }

    @Test
    fun `a preset and a typed delay are what refusal judges`(): Unit = runBlocking {
        val viewModel = loadedWithName()

        viewModel.setGuardianDelay(4_320)
        assertEquals(4_320L, viewModel.guardianDelay())
        assertNull(viewModel.refusal()?.takeIf { "delay" in it })

        viewModel.useCustomDelay()
        viewModel.setGuardianDelayInput("2,016")
        assertEquals(2_016L, viewModel.guardianDelay(), "grouping is dropped as it is typed")
        assertTrue(viewModel.isCustomDelay.value)
    }

    @Test
    fun `a delay below six or above 65535 is refused with its reason`(): Unit = runBlocking {
        val viewModel = loadedWithName()
        viewModel.useCustomDelay()

        viewModel.setGuardianDelayInput("5")
        assertEquals("A delay is at least 6 blocks.", viewModel.refusal())

        viewModel.setGuardianDelayInput("65536")
        assertEquals(65_536L, viewModel.guardianDelay(), "kept as typed, so the field can say what is wrong with it")
        assertEquals("A delay is at most 65,535 blocks.", viewModel.refusal())

        viewModel.setGuardianDelayInput("")
        assertEquals("Give the delay in blocks.", viewModel.refusal())
    }

    @Test
    fun `an unguarded subgroup is not held to a delay`(): Unit = runBlocking {
        val viewModel = loadedWithName()
        viewModel.useCustomDelay()
        viewModel.setGuardianDelayInput("5")
        viewModel.setWalletPolicy(SubgroupWalletPolicy.UNGUARDED)

        assertNull(viewModel.refusal()?.takeIf { "delay" in it })
    }

    @Test
    fun `a retry starts from the failed ceremony's delay`(): Unit = runBlocking {
        val preset = loadedWithName(failed(walletPolicy = SubgroupWalletPolicy.GUARDED, guardianDelay = 1_008))
        assertEquals(1_008L, preset.guardianDelay())
        assertFalse(preset.isCustomDelay.value, "a preset comes back as the preset")

        val typed = loadedWithName(failed(walletPolicy = SubgroupWalletPolicy.GUARDED, guardianDelay = 2_016))
        assertEquals(2_016L, typed.guardianDelay())
        assertTrue(typed.isCustomDelay.value, "anything else comes back in the field")
    }
}
