package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.managers.GroupWavelengthPayouts
import com.machankura.dedun.compose.managers.MemberBalances
import com.machankura.dedun.compose.managers.MemberTransfers
import com.machankura.dedun.compose.nostr.wallet.MemberTransferEvent
import com.machankura.dedun.compose.nostr.wallet.TransferRequestEvent
import com.machankura.dedun.compose.nostr.wallet.TransferRequestEvent.Rail
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.repository.PayoutDestinationRepository
import com.machankura.dedun.compose.repository.PayoutRepository
import com.machankura.dedun.compose.repository.TransferRepository
import com.machankura.dedun.compose.text.ProposedEvent
import com.machankura.dedun.compose.ui.GroupWavelengthUiFixtures
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.view.model.GroupTransferViewModel
import com.machankura.dedun.compose.ui.view.state.GroupPayoutsUIState
import com.machankura.dedun.compose.ui.view.state.GroupTransferUIState
import com.machankura.dedun.compose.wallet.CoinOwnership
import com.machankura.dedun.compose.wallet.PayoutRule
import com.machankura.dedun.compose.wallet.lightning.LightningBalance
import com.vitorpamplona.quartz.nip01Core.core.Event
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Transferring part of a balance to another member, on the screen: `docs/member-transfers.md`, Phase 4. */
@OptIn(ExperimentalTestApi::class)
class GroupTransferScreenJvmTest {
    private val chain = Chain.Regtest
    private val room: LocalChatRoom = GroupWavelengthUiFixtures.localChatRoom
    private val me = GroupWavelengthUiFixtures.ME
    private val bob = GroupWavelengthUiFixtures.OTHER
    private val carol = "c".repeat(64)
    private val names = mapOf(me to "Alice", bob to "Bob", carol to "Carol")

    private class Recording : TransferRepository by TransferRepository.NO_OP {
        val asked = mutableListOf<Triple<Rail, String, Long>>()
        override suspend fun request(localChatRoom: LocalChatRoom, chain: Chain, rail: Rail, to: String, amountSat: Long, note: String?): FrostSigningSession? {
            asked += Triple(rail, to, amountSat)
            return null
        }
    }

    private fun loaded(givable: TransferRepository.Givable = TransferRepository.Givable(30_000, 30_000), canAsk: Boolean = true) = GroupTransferUIState.Loaded(
        localChatRoom = room,
        givable = givable,
        receivers = listOf(TransferRepository.Receiver(bob, true), TransferRepository.Receiver(carol, false)),
        names = names,
        canAsk = canAsk,
    )

    private fun render(state: GroupTransferUIState, repository: TransferRepository = TransferRepository.NO_OP, test: androidx.compose.ui.test.ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = 420, height = 2000) {
            setContent {
                DedunTheme {
                    ProvideSnackbarHost {
                        Box(modifier = Modifier.fillMaxSize()) {
                            GroupTransferScreen(
                                activeUserPublicKey = me, chatRoomId = room.chatRoom.id, chain = chain, rail = Rail.WAVELENGTH,
                                initialUIState = state, chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY, transferRepository = repository,
                                onNavigateBack = {},
                            )
                        }
                    }
                }
            }
            test()
        }

    @Test
    fun `choosing whom and how much shows what the receiver will hold, and that nothing leaves`() {
        val repository = Recording()
        render(loaded(), repository) {
            onNodeWithText("Ask the group to record it").assertIsNotEnabled()
            onNodeWithText("@Bob").performClick()
            onNodeWithText("Amount").performTextInput("20000")
            onNodeWithText("@Bob will hold 20,000 sats more in the Wavelength wallet", substring = true).assertIsDisplayed()
            onNodeWithText("Ask the group to record it").assertIsEnabled().performClick()
            val deadline = System.currentTimeMillis() + 5_000
            while (repository.asked.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals(listOf(Triple(Rail.WAVELENGTH, bob, 20_000L)), repository.asked)
        }
    }

    @Test
    fun `more than the member has, or less than a transfer moves, says so and can't be sent`() {
        render(loaded()) {
            onNodeWithText("@Bob").performClick()
            onNodeWithText("Amount").performTextInput("30001")
            onNodeWithText("1 sats more than you have").assertIsDisplayed()
            onNodeWithText("Ask the group to record it").assertIsNotEnabled()
        }
        render(loaded()) {
            onNodeWithText("@Bob").performClick()
            onNodeWithText("Amount").performTextInput("500")
            onNodeWithText("A transfer is at least 546 sats").assertIsDisplayed()
        }
    }

    @Test
    fun `a member the group hasn't opened deposits for is listed and can't be chosen`() {
        render(loaded()) {
            onNodeWithText("Hasn't started depositing here").assertIsDisplayed()
            onNodeWithText("@Carol").performClick()
            onNodeWithText("@Carol").assertIsNotSelected()
            onNodeWithText("@Bob").performClick()
            onNodeWithText("@Bob").assertIsSelected()
        }
    }

    @Test
    fun `everything sends the whole ledger balance`() {
        val repository = Recording()
        render(loaded(TransferRepository.Givable(20_000, 18_000)), repository) {
            onNodeWithText("@Bob").performClick()
            onNodeWithText("Everything").performClick()
            onNodeWithText("@Bob will hold 18,000 sats more", substring = true).assertIsDisplayed()
            onNodeWithText("On the group's ledger that is 20,000 sats", substring = true).assertIsDisplayed()
            onNodeWithText("Ask the group to record it").performClick()
            val deadline = System.currentTimeMillis() + 5_000
            while (repository.asked.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals(20_000L, repository.asked.single().third)
        }
    }

    @Test
    fun `a profile that can't sign, or a member with nothing to give, is told so instead of a form`() {
        render(loaded(canAsk = false)) { onNodeWithText("This profile can't sign a transfer", substring = true).assertIsDisplayed() }
        render(loaded(TransferRepository.Givable(545, 545))) { onNodeWithText("You have nothing to transfer in this wallet").assertIsDisplayed() }
    }

    @Test
    fun `the form's rules, and the ledger arithmetic under a shortfall`() {
        assertEquals(GroupTransferViewModel.Problem.NoReceiver, GroupTransferViewModel.problemWith(null, 1_000, 10_000))
        assertEquals(GroupTransferViewModel.Problem.NoAmount, GroupTransferViewModel.problemWith(bob, null, 10_000))
        assertEquals(GroupTransferViewModel.Problem.BelowFloor, GroupTransferViewModel.problemWith(bob, 545, 10_000))
        assertEquals(GroupTransferViewModel.Problem.OverBalance(1), GroupTransferViewModel.problemWith(bob, 10_001, 10_000))
        assertNull(GroupTransferViewModel.problemWith(bob, 10_000, 10_000))

        val short = TransferRepository.Givable(givableSat = 20_000, heldSat = 18_000)
        assertEquals(10_000, short.ledgerFor(9_000))
        assertEquals(9_000, short.arrivesAs(10_000))
        (546L..20_000L step 7).forEach { typed ->
            assertTrue(short.arrivesAs(short.ledgerFor(typed)) >= typed, "rounded up, so what arrives is never less than $typed, which was typed")
        }
        val whole = TransferRepository.Givable(20_000, 20_000)
        assertEquals(9_000, whole.ledgerFor(9_000))
    }

    @Test
    fun `a co-signer reads who gives what to whom, that the giver signed it, and that nothing leaves`() {
        val alice = PrivateKey(ByteVector32(Hex.decode("a11ce".padStart(64, '0'))))
        val aliceKey = alice.xOnlyPublicKey().value.toHex()
        val signed = TransferRequestEvent.build(alice, room.chatRoom.id, chain, Rail.WAVELENGTH, bob, 20_000, 1_700_086_400, "groceries", 1_700_000_000)
        val template = MemberTransferEvent.template(TransferRequestEvent.verified(signed) as TransferRequestEvent.Request, createdAt = 1_700_000_100)
        val event = Event("00".repeat(32), room.chatRoom.id, template.createdAt, template.kind, template.tags, template.content, "")
        val summary = ProposedEvent.summarize(event) { mapOf(aliceKey to "Alice", bob to "Bob")[it] }
        assertEquals("@Alice transfers 20,000 sats of their Wavelength balance to @Bob", summary.label)
        assertTrue("Signed by @Alice themselves" in summary.detail && "Nothing leaves the wallet" in summary.detail && "groceries" in summary.detail, summary.detail)
    }

    // --- the payouts screen ---

    private fun memberBalance(wavelength: GroupWavelengthPayouts.Balance, lightningMsat: Long = 0) = MemberBalances.MemberBalance(
        member = me, destination = null, isFormerMember = false, coins = emptyList(),
        lightning = LightningBalance.Balance(lightningMsat, 0, 0), wavelength = wavelength,
    )

    @Test
    fun `your own row offers each rail you hold enough on, where transfers are offered`() {
        val both = memberBalance(GroupWavelengthPayouts.Balance(depositedSat = 10_000, availableSat = 10_000, heldSat = 10_000), lightningMsat = 5_000_000)
        assertEquals(listOf(Rail.WAVELENGTH, Rail.LIGHTNING), transferRailsOf(both, Chain.Regtest))
        assertEquals(emptyList(), transferRailsOf(both, Chain.Mainnet), "not on mainnet yet")
        val little = memberBalance(GroupWavelengthPayouts.Balance(depositedSat = 545, availableSat = 545, heldSat = 545))
        assertEquals(emptyList(), transferRailsOf(little, Chain.Regtest))
    }

    @Test
    fun `the payouts screen lists transfers waiting, with what this device can do about each`() {
        val alice = PrivateKey(ByteVector32(Hex.decode("a11ce".padStart(64, '0'))))
        val aliceKey = alice.xOnlyPublicKey().value.toHex()
        val request = TransferRequestEvent.verified(
            TransferRequestEvent.build(alice, room.chatRoom.id, chain, Rail.WAVELENGTH, bob, 20_000, 1_700_086_400, "groceries", 1_700_000_000)
        ) as TransferRequestEvent.Request
        val state = GroupPayoutsUIState.Loaded(
            localChatRoom = room,
            balances = MemberBalances.Balances(mapOf(me to memberBalance(GroupWavelengthPayouts.Balance(receivedSat = 5_000, availableSat = 5_000, heldSat = 5_000))), emptyList()),
            walletState = null, reserved = emptySet(), context = PayoutRule.Context(CoinOwnership.Records.NONE, emptyMap()),
            names = names + (aliceKey to "Alice"), canSign = true, self = me,
            transfersWaiting = listOf(MemberTransfers.Asked(request, MemberTransfers.State.WAITING)),
            canAskTransfer = true,
        )
        runDesktopComposeUiTest(width = 420, height = 2400) {
            setContent {
                DedunTheme {
                    ProvideSnackbarHost {
                        GroupPayoutsScreen(
                            activeUserPublicKey = me, chatRoomId = room.chatRoom.id, chain = chain, initialUIState = state,
                            chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY, frostSigningRepository = FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY,
                            payoutRepository = PayoutRepository.NO_OP, payoutDestinationRepository = PayoutDestinationRepository.NO_OP, wallets = null,
                            onNavigateBack = {},
                        )
                    }
                }
            }
            onNodeWithText("Transfers waiting").assertIsDisplayed()
            onNodeWithText("@Alice asks to transfer 20,000 sats of their Wavelength balance to @Bob").assertIsDisplayed()
            onNodeWithText("Put to the group").assertIsDisplayed()
            // Not the giver's own request on this device: nothing to take back.
            assertEquals(0, onAllNodesWithText("Take back").fetchSemanticsNodes().size)
            // A balance made only of what was received still shows, and says where it came from.
            onNodeWithText("Includes 5,000 sats received from members").assertIsDisplayed()
            onAllNodesWithText("Transfer from Wavelength to a member").onFirst().assertIsDisplayed()
        }
    }
}
