package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.GroupFixtures
import com.machankura.dedun.compose.nostr.wallet.MemberWalletPolicyEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.repository.MemberWalletRepository
import com.machankura.dedun.compose.ui.view.state.MemberWalletPolicyUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.Kind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Opening a group to its members' wallets, changing the delay, and closing it --
 * `docs/member-wallet-delay.md`, Phase 6. The repositories are fakes: what is
 * under test is what the screen offers and what it proposes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MemberWalletPolicyViewModelJvmTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val group = GroupFixtures.keyMaterial("6a0b")
    private val chatRoomId = GroupFixtures.roomOf(group)
    private val me = "a".repeat(64)

    private val room = LocalChatRoom(
        chatRoom = ChatRoom(id = chatRoomId, userPublicKey = me, subject = "#Council", description = null, mlsGroupState = "state")
    )

    private fun chat(canSign: Boolean) = object : ChatRepository by ChatRepository.NO_OP_CHAT_REPOSITORY {
        override suspend fun getChatRoomByIdentifier(identifier: String): LocalChatRoom? = room
        override suspend fun canSign(chatRoomId: String): Boolean = canSign
    }

    private fun members(policy: MemberWalletPolicyEvent.Policy?) = object : MemberWalletRepository by MemberWalletRepository.NO_OP {
        override suspend fun policyOf(chatRoomId: String): MemberWalletPolicyEvent.Policy? = policy
    }

    private class Proposing : FrostSigningRepository by FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY {
        var proposed: Pair<Kind, Array<Array<String>>>? = null
        override suspend fun proposeSigning(
            localChatRoom: LocalChatRoom,
            userPublicKey: HexKey,
            kind: Kind,
            tags: Array<Array<String>>,
            content: String,
        ): FrostSigningSession? {
            proposed = kind to tags
            return null
        }
    }

    private fun open(delay: Long) = MemberWalletPolicyEvent.verify(GroupFixtures.memberWalletPolicy(group, delay), chatRoomId)
    private fun closed() = MemberWalletPolicyEvent.verify(GroupFixtures.memberWalletPolicy(group, null), chatRoomId)

    private fun loaded(policy: MemberWalletPolicyEvent.Policy?, canSign: Boolean = true, signing: FrostSigningRepository = Proposing()) =
        MemberWalletPolicyViewModel(me, chatRoomId, MemberWalletPolicyUIState.Loading, chat(canSign), members(policy), signing).apply {
            initiate()
            runBlocking { withTimeout(5_000) { while (uiState !is MemberWalletPolicyUIState.Loaded) delay(10) } }
        }

    private fun Proposing.awaitProposed() = runBlocking { withTimeout(5_000) { while (proposed == null) delay(10) } }.let { assertNotNull(proposed) }

    @Test
    fun `a group opening for the first time starts at 12, an open one at its own delay`() {
        assertEquals(12L, loaded(null).delay.delay())
        assertEquals(12L, loaded(closed()).delay.delay())
        assertEquals(4_320L, loaded(open(4_320)).delay.delay())
        assertTrue(loaded(open(4_320)).delay.isCustom.value.not(), "a preset comes back as its chip")
        assertTrue(loaded(open(2_016)).delay.isCustom.value, "anything else in the field")
    }

    @Test
    fun `opening proposes an open policy at the chosen delay`() {
        val signing = Proposing()
        val viewModel = loaded(null, signing = signing)
        viewModel.delay.pick(1_008)
        assertNull(viewModel.openProblem())

        viewModel.proposeOpen(onSuccess = {}, onFailure = {})
        val (kind, tags) = signing.awaitProposed()
        assertEquals(MemberWalletPolicyEvent.KIND, kind)
        assertTrue(tags.any { it.toList() == listOf("guardian_delay", "1008") })
    }

    @Test
    fun `a delay out of range, or the group's own, is not proposed`() {
        val viewModel = loaded(open(144))
        assertEquals("That is already this group's delay.", viewModel.openProblem())

        viewModel.delay.useCustom()
        viewModel.delay.type("5")
        assertEquals("A delay is at least 6 blocks.", viewModel.openProblem())
        viewModel.delay.type("65536")
        assertEquals("A delay is at most 65,535 blocks.", viewModel.openProblem())
    }

    @Test
    fun `closing is offered only to an open group, and proposes a closed policy`() {
        assertFalse(loaded(null).canClose())
        assertFalse(loaded(closed()).canClose())

        val signing = Proposing()
        val viewModel = loaded(open(144), signing = signing)
        assertTrue(viewModel.canClose())
        viewModel.proposeClose(onSuccess = {}, onFailure = {})
        val (_, tags) = signing.awaitProposed()
        assertTrue(tags.any { it.toList() == listOf("closed") })
    }

    @Test
    fun `a device that cannot sign is offered nothing to propose`() {
        val viewModel = loaded(open(144), canSign = false)
        assertIs<MemberWalletPolicyUIState.Loaded>(viewModel.uiState)
        assertNotNull(viewModel.openProblem())
        assertFalse(viewModel.canClose())
    }
}
