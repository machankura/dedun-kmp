package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.MemberWalletManager.Refusal
import com.machankura.dedun.compose.repository.MemberWalletRepository
import com.machankura.dedun.compose.ui.view.state.NewMemberWalletUIState
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import fr.acinq.bitcoin.Chain
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
import kotlin.test.assertIs

/**
 * Which groups the *Choose a group to guard it* list offers --
 * `docs/member-wallet-delay.md`. Only a group that guards members' wallets, or
 * may: one whose key this device has not been told is kept, with the button
 * that asks, because the answer is how it would learn the group is open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewMemberWalletViewModelJvmTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val me = "a".repeat(64)

    private fun room(id: Char) = LocalChatRoom(
        chatRoom = ChatRoom(id = id.toString().repeat(64), userPublicKey = me, subject = "#$id", description = null, mlsGroupState = "state")
    )

    /** One group per refusal, and one that can be asked. */
    private val refusals: Map<LocalChatRoom, Refusal?> = mapOf(
        room('1') to null,
        room('2') to Refusal.KEY_UNKNOWN,
        room('3') to Refusal.NOT_OFFERED,
        room('4') to Refusal.CLOSED,
        room('5') to Refusal.GUARDIANS_UNREADABLE,
    )

    private fun repository(groups: Map<LocalChatRoom, Refusal?>) = object : MemberWalletRepository by MemberWalletRepository.NO_OP {
        override suspend fun groupsOf(ownerPublicKey: HexKey): List<LocalChatRoom> = groups.keys.toList()
        override suspend fun refusal(chatRoomId: String, chain: Chain): Refusal? =
            groups.entries.first { it.key.chatRoom.id == chatRoomId }.value
    }

    private fun picking(groups: Map<LocalChatRoom, Refusal?>): NewMemberWalletUIState.Picking {
        val viewModel = NewMemberWalletViewModel(me, null, Chain.Regtest, NewMemberWalletUIState.Loading, repository(groups))
        viewModel.initiate()
        runBlocking { withTimeout(5_000) { while (viewModel.uiState == NewMemberWalletUIState.Loading) delay(10) } }
        return assertIs<NewMemberWalletUIState.Picking>(viewModel.uiState)
    }

    @Test
    fun `a group that never opened, or has closed, is not on the list`() {
        val listed = picking(refusals).groups.associate { it.room.chatRoom.subject to it.refusal }

        assertEquals(
            mapOf<String?, Refusal?>("#1" to null, "#2" to Refusal.KEY_UNKNOWN, "#5" to Refusal.GUARDIANS_UNREADABLE),
            listed,
            "an open group, one whose key is not known yet, and one this build cannot read stay, each with its reason",
        )
    }

    @Test
    fun `a member none of whose groups guards members' wallets sees an empty list`() {
        assertEquals(emptyList(), picking(mapOf(room('3') to Refusal.NOT_OFFERED, room('4') to Refusal.CLOSED)).groups)
    }
}
