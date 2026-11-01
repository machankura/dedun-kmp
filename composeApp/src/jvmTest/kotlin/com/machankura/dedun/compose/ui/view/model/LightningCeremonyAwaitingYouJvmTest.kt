package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.FrostSigningItem
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.IcebergKeySession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalFrostSigningSession
import com.machankura.dedun.compose.database.model.types.FrostSigningStage
import com.machankura.dedun.compose.database.model.types.IcebergApprovalStep
import com.machankura.dedun.compose.database.model.types.IcebergRitualStage
import com.machankura.dedun.compose.nostr.lightning.GroupLightningWalletStatementEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.DkgRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.repository.GroupLightningRepository
import com.machankura.dedun.compose.repository.NostrRepository
import com.machankura.dedun.compose.ui.view.state.ChatMessageListUIState
import com.machankura.dedun.compose.wallet.GroupLightningRollout
import fr.acinq.bitcoin.Chain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Lightning wallet's key ceremony, in the notice above the composer
 * (docs/group-lightning-transcript.md, Phase 2): asked of the member at each of its
 * gates, and of the member who opened it once the key is made and no wallet uses it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LightningCeremonyAwaitingYouJvmTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val chatRoomId = "d4c3b2a1".repeat(8)
    private val me = "a".repeat(64)
    private val them = "b".repeat(64)
    private val localChatRoom = LocalChatRoom(chatRoom = ChatRoom(id = chatRoomId, userPublicKey = me, subject = "Treasury", description = null, mlsGroupState = "state"))

    private fun ceremony(
        stage: IcebergRitualStage,
        coordinator: String = them,
        id: String = "ab".repeat(32),
    ) = IcebergKeySession(
        id = id, chatRoomId = chatRoomId, coordinatorPublicKey = coordinator, userPublicKey = me, threshold = 2, participantCount = 4,
        participantPublicKeys = IcebergKeySession.formatParticipants(listOf(me, them, "c".repeat(64), "d".repeat(64))),
        onchainDkgSessionId = "x", stage = stage,
        share = if (stage == IcebergRitualStage.COMPLETE) "00" else null,
    )

    private class Lightning(
        val ceremonies: List<IcebergKeySession>,
        val gate: IcebergApprovalStep? = null,
        val unused: IcebergKeySession? = null,
    ) : GroupLightningRepository by GroupLightningRepository.NO_OP_GROUP_LIGHTNING_REPOSITORY {
        override fun observeCeremonies(chatRoomId: String): Flow<List<IcebergKeySession>> = flowOf(ceremonies)
        override suspend fun pendingApproval(session: IcebergKeySession): IcebergApprovalStep? =
            gate.takeIf { session.stage != IcebergRitualStage.COMPLETE }
        override suspend fun unusedKey(chatRoomId: String, chain: Chain): IcebergKeySession? = unused
    }

    private class Signings(val sessions: List<LocalFrostSigningSession>) :
        FrostSigningRepository by FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY {
        override fun observeSessionsForChatRoom(chatRoomId: String): Flow<List<LocalFrostSigningSession>> = flowOf(sessions)
    }

    /** A signing session proposing a wallet statement over [ceremonyId]. */
    private fun statementBeingSigned(ceremonyId: String): LocalFrostSigningSession {
        val statement = GroupLightningWalletStatementEvent.Statement(
            GroupLightningRollout.NETWORK, 0, 0, "02" + "11".repeat(32), ceremonyId, 2,
            listOf(me, them), "02" + "33".repeat(32), "02" + "44".repeat(32), GroupLightningWalletStatementEvent.Policy.DEFAULT,
        )
        val template = GroupLightningWalletStatementEvent.template(statement, createdAt = 1)
        val tags = template.tags.joinToString(",", "[", "]") { tag -> tag.joinToString(",", "[", "]") { "\"$it\"" } }
        return LocalFrostSigningSession(
            session = FrostSigningSession(
                id = "s1", chatRoomId = chatRoomId, coordinatorPublicKey = them, userPublicKey = me, dkgSessionId = "x",
                threshold = 2, participantCount = 4, signerId = 1, stage = FrostSigningStage.COLLECTING_NONCES,
            ),
            unorderedItems = listOf(
                FrostSigningItem(
                    sessionId = "s1", itemIndex = 0, eventId = "e".repeat(64), nonceRandom = "00".repeat(32),
                    unsignedEventJson = """{"id":"${"e".repeat(64)}","pubkey":"$chatRoomId","created_at":1,""" +
                        """"kind":${GroupLightningWalletStatementEvent.KIND},"tags":$tags,"content":"","sig":""}""",
                ),
            ),
        )
    }

    private fun awaiting(
        lightning: GroupLightningRepository,
        signings: List<LocalFrostSigningSession> = emptyList(),
        expectNone: Boolean = false,
    ): List<ChatMessageListViewModel.AwaitingCeremony> {
        val viewModel = ChatMessageListViewModel(
            initialChatMessageListUIState = ChatMessageListUIState.Loading,
            localChatRoom = localChatRoom,
            nostrRepository = NostrRepository.NO_OP_NOSTR_REPOSITORY,
            chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY,
            frostSigningRepository = Signings(signings),
            dkgRepository = DkgRepository.NO_OP_DKG_REPOSITORY,
            lightningRepository = lightning,
        )
        viewModel.initiate()
        return runBlocking {
            // Settles on the IO dispatcher. Nothing to wait for when the answer is none,
            // so a short wait that finds nothing is the pass.
            withTimeoutOrNull(if (expectNone) 500 else 5_000) {
                while (viewModel.ceremoniesAwaitingYou.isEmpty()) delay(10)
            }
            viewModel.ceremoniesAwaitingYou
        }
    }

    @Test
    fun `each of the ceremony's gates is asked of the member above the composer`() {
        IcebergApprovalStep.entries.forEach { step ->
            val running = ceremony(IcebergRitualStage.COLLECTING_REVEALS)
            val asked = awaiting(Lightning(listOf(running), gate = step)).single()

            assertEquals(running.id, asked.sessionId)
            assertEquals(ChatMessageListViewModel.Ask.LightningKeyStep(step), asked.ask)
            assertEquals(false, asked.isSubgroup)
        }
    }

    @Test
    fun `a ceremony waiting on others, or abandoned, asks nothing`() {
        assertTrue(awaiting(Lightning(listOf(ceremony(IcebergRitualStage.COLLECTING_COMMITMENTS))), expectNone = true).isEmpty())
        assertTrue(awaiting(Lightning(listOf(ceremony(IcebergRitualStage.FAILED)), gate = IcebergApprovalStep.JOIN), expectNone = true).isEmpty())
    }

    @Test
    fun `a finished key no wallet uses is asked of whoever opened the ceremony`() {
        val mine = ceremony(IcebergRitualStage.COMPLETE, coordinator = me)
        val asked = awaiting(Lightning(listOf(mine), unused = mine)).single()

        assertEquals(mine.id, asked.sessionId)
        assertEquals(ChatMessageListViewModel.Ask.LightningWalletOpening, asked.ask)
    }

    @Test
    fun `and of nobody else`() {
        val theirs = ceremony(IcebergRitualStage.COMPLETE, coordinator = them)
        assertTrue(awaiting(Lightning(listOf(theirs), unused = theirs), expectNone = true).isEmpty())
    }

    @Test
    fun `nor once a wallet statement for it is being signed`() {
        val mine = ceremony(IcebergRitualStage.COMPLETE, coordinator = me)
        assertTrue(
            awaiting(Lightning(listOf(mine), unused = mine), signings = listOf(statementBeingSigned(mine.id)), expectNone = true).isEmpty(),
            "the key has been put to the group; asking again would start a second proposal",
        )
    }
}
