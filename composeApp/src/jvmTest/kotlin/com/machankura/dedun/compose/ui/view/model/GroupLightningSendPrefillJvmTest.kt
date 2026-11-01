package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.database.model.ChatRoom
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.lightning.GroupLightningAuthorisationEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.GroupLightningRepository
import com.machankura.dedun.compose.ui.view.state.GroupLightningSendUIState
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

/**
 * *Pay over Lightning* on a member's sheet opens the send form with the invoice their address gave --
 * `docs/group-payout-destination.md`, Phase 7.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupLightningSendPrefillJvmTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val chatRoomId = "9a17".repeat(16)
    private val room = LocalChatRoom(chatRoom = ChatRoom(id = chatRoomId, userPublicKey = "a".repeat(64), subject = "#Council", description = null, mlsGroupState = "state"))
    private val chat = object : ChatRepository by ChatRepository.NO_OP_CHAT_REPOSITORY {
        override suspend fun getChatRoomByIdentifier(identifier: String): LocalChatRoom? = room
    }

    @Test
    fun `the invoice from a member's address starts the form`() {
        val viewModel = GroupLightningSendViewModel(
            "a".repeat(64), chatRoomId, Chain.Regtest, GroupLightningSendUIState.Loading, chat,
            GroupLightningRepository.NO_OP_GROUP_LIGHTNING_REPOSITORY,
            initialInvoice = "lnbcrt210u1example",
            payout = GroupLightningAuthorisationEvent.Payout("b".repeat(64), "de".repeat(32)),
        )
        viewModel.initiate()
        runBlocking { withTimeout(5_000) { while (viewModel.invoiceField.text.isEmpty()) delay(10) } }
        assertEquals("lnbcrt210u1example", viewModel.invoiceField.text.toString())
    }
}
