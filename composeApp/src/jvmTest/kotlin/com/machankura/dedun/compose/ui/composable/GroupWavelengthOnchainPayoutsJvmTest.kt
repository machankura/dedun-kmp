package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.machankura.dedun.compose.database.model.FrostSigningSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.wallet.CoinOwnership
import com.machankura.dedun.compose.managers.GroupWavelengthPayouts
import com.machankura.dedun.compose.managers.GroupWavelengthWalletManager
import com.machankura.dedun.compose.managers.MemberBalances
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestination
import com.machankura.dedun.compose.nostr.wallet.GroupPayoutDestinationEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthOnchainPayoutEvent
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
import com.machankura.dedun.compose.repository.GroupWavelengthOnchainPayout
import com.machankura.dedun.compose.repository.GroupWavelengthRepository
import com.machankura.dedun.compose.repository.PayoutDestinationRepository
import com.machankura.dedun.compose.repository.PayoutRepository
import com.machankura.dedun.compose.text.LightningAddress
import com.machankura.dedun.compose.ui.GroupWavelengthUiFixtures
import com.machankura.dedun.compose.ui.GroupWavelengthUiFixtures.ME
import com.machankura.dedun.compose.ui.GroupWavelengthUiFixtures.OTHER
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.view.model.GroupPayoutsViewModel
import com.machankura.dedun.compose.ui.view.state.GroupPayoutsUIState
import com.machankura.dedun.compose.ui.view.state.GroupWavelengthWalletUIState
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.wallet.PayoutRule
import com.machankura.dedun.compose.wallet.UnusedAddressCheck
import com.machankura.dedun.compose.wallet.lightning.LightningBalance
import fr.acinq.bitcoin.ByteVector
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.secp256k1.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A member's Wavelength balance paid out onchain, on the payouts screen and the wallet's (`docs/group-wavelength-payouts.md`, Phase 4):
 * the whole balance less the most a round may cost, to the address they gave, once it's been checked for use.
 */
@OptIn(ExperimentalTestApi::class)
class GroupWavelengthOnchainPayoutsJvmTest {
    private val chain = Chain.Regtest
    private val alice = PrivateKey(ByteVector32(Hex.decode("a11ce".padStart(64, '0'))))
    private val aliceKey = alice.xOnlyPublicKey().value.toHex()
    private val address = "bcrt1qzyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3lgth6c"
    private val wallet = GroupWavelengthUiFixtures.wallet(chain)
    private val room: LocalChatRoom = GroupWavelengthUiFixtures.localChatRoom

    private fun destination(onchain: String? = address) = GroupPayoutDestinationEvent.verified(
        GroupPayoutDestinationEvent.build(alice, room.chatRoom.id, chain, LightningAddress("alice", "example.com"), onchainAddress = onchain)
    ) as GroupPayoutDestination

    private fun balance(availableSat: Long, openSat: Long = 0, onchain: String? = address) = MemberBalances.MemberBalance(
        member = aliceKey,
        destination = destination(onchain),
        isFormerMember = false,
        coins = emptyList(),
        lightning = LightningBalance.Balance(0, 0, 0),
        wavelength = GroupWavelengthPayouts.Balance(depositedSat = availableSat + openSat, openSat = openSat, availableSat = availableSat),
    )

    private fun loaded(balance: MemberBalances.MemberBalance, paysOnchain: Boolean = true, paysLightning: Boolean = false) = GroupPayoutsUIState.Loaded(
        localChatRoom = room,
        balances = MemberBalances.Balances(mapOf(aliceKey to balance), emptyList()),
        walletState = null,
        reserved = emptySet(),
        context = PayoutRule.Context(CoinOwnership.Records.NONE, emptyMap()),
        names = mapOf(aliceKey to "Alice"),
        canSign = true,
        self = ME,
        wavelengthWallet = wallet,
        wavelengthPaysOnchain = paysOnchain,
        wavelengthPaysLightning = paysLightning,
    )

    private class Proposing : GroupWavelengthRepository by GroupWavelengthRepository.NO_OP {
        val proposed = mutableListOf<Pair<Long, Long>>()
        override suspend fun proposeOnchainPayout(localChatRoom: LocalChatRoom, userPublicKey: String, wallet: GroupWavelengthWalletManager.Wallet, destination: GroupPayoutDestination, amountSat: Long, maxFeeSat: Long): FrostSigningSession {
            proposed += amountSat to maxFeeSat
            error("not here")
        }
    }

    private fun viewModel(state: GroupPayoutsUIState.Loaded, repository: GroupWavelengthRepository = GroupWavelengthRepository.NO_OP) = GroupPayoutsViewModel(
        ME, room.chatRoom.id, chain, state, ChatRepository.NO_OP_CHAT_REPOSITORY, FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY, PayoutRepository.NO_OP,
        PayoutDestinationRepository.NO_OP, null, wavelengthRepository = repository,
    )

    @Test
    fun `the whole balance less the most a round may cost, to the address the member gave`() {
        val balance = balance(10_000)
        val offer = assertIs<GroupPayoutsViewModel.WavelengthOnchainOffer.Offer>(viewModel(loaded(balance)).wavelengthOnchainOffer(balance))
        assertEquals(GroupPayoutsViewModel.WavelengthOnchainOffer.Offer(8_000, 2_000, address), offer)
    }

    @Test
    fun `no address, too little, or a payout already waiting each say so instead`() {
        val noAddress = balance(10_000, onchain = null)
        assertEquals(GroupPayoutsViewModel.WavelengthOnchainOffer.NoAddress, viewModel(loaded(noAddress)).wavelengthOnchainOffer(noAddress))
        val little = balance(2_500)
        assertEquals(GroupPayoutsViewModel.WavelengthOnchainOffer.TooLittle(500), viewModel(loaded(little)).wavelengthOnchainOffer(little))
        val waiting = balance(0, openSat = 5_000)
        assertEquals(GroupPayoutsViewModel.WavelengthOnchainOffer.Waiting, viewModel(loaded(waiting)).wavelengthOnchainOffer(waiting))
    }

    @Test
    fun `nothing is offered where onchain payouts are off, or the member has nothing in the wallet`() {
        val balance = balance(10_000)
        assertNull(viewModel(loaded(balance, paysOnchain = false)).wavelengthOnchainOffer(balance))
        val empty = balance(0)
        assertNull(viewModel(loaded(empty)).wavelengthOnchainOffer(empty))
    }

    @Test
    fun `nothing is proposed until the address is known to be unused`() {
        val balance = balance(10_000)
        val repository = Proposing()
        val model = viewModel(loaded(balance), repository)
        model.payOutWavelengthOnchain(aliceKey, {}, {})
        assertEquals(emptyList(), repository.proposed, "still checking")
        model.usage[aliceKey] = UnusedAddressCheck.Usage.Used
        model.payOutWavelengthOnchain(aliceKey, {}, {})
        assertEquals(emptyList(), repository.proposed, "paid before")
    }

    @Test
    fun `the row says what arrives where, and pays only once the address has been checked`() = render(balance(10_000), UnusedAddressCheck.Usage.Unused) {
        onNodeWithText("Pays @Alice 8,000 sats onchain to $address in the wallet's next round, and up to 2,000 sats in fees from their balance.").assertIsDisplayed()
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithText("Pay out onchain from the Wavelength wallet").assertIsEnabled() }.isSuccess }
    }

    @Test
    fun `an address paid before holds the button back and says why`() = render(balance(10_000), UnusedAddressCheck.Usage.Used) {
        waitUntil(timeoutMillis = 5_000) { runCatching { onNodeWithText("Paid before. Ask @Alice for a new address.").assertIsDisplayed() }.isSuccess }
        onNodeWithText("Pay out onchain from the Wavelength wallet").assertIsNotEnabled()
    }

    @Test
    fun `a member with no onchain address is told it can only be paid over Lightning`() = render(balance(10_000, onchain = null), UnusedAddressCheck.Usage.Unused) {
        onNodeWithText("@Alice hasn't given an onchain address, so their Wavelength balance can only be paid over Lightning.").assertIsDisplayed()
    }

    @Test
    fun `a member with both addresses is offered each rail, by name`() = render(balance(10_000), UnusedAddressCheck.Usage.Unused, paysLightning = true) {
        onNodeWithText("Pay out over Lightning from the Wavelength wallet").assertIsDisplayed()
        onNodeWithText("Pay out onchain from the Wavelength wallet").assertIsDisplayed()
    }

    @Test
    fun `a payout waiting on either rail holds the Lightning one back too`() {
        val waiting = balance(5_000, openSat = 5_000)
        assertNull(viewModel(loaded(waiting, paysLightning = true)).wavelengthPayable(waiting))
        val free = balance(5_000)
        assertIs<Pair<Long, Long>>(viewModel(loaded(free, paysLightning = true)).wavelengthPayable(free))
    }

    private fun render(balance: MemberBalances.MemberBalance, usage: UnusedAddressCheck.Usage, paysLightning: Boolean = false, assertions: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = 400, height = 2000) {
            val destinations = object : PayoutDestinationRepository by PayoutDestinationRepository.NO_OP {
                override suspend fun usageOf(chatRoomId: String, chain: Chain, script: ByteVector) = usage
            }
            val state = loaded(balance, paysLightning = paysLightning).copy(context = PayoutRule.Context(CoinOwnership.Records.NONE, balance.destination?.onchainAddress?.let { mapOf(aliceKey to ByteVector("0014" + "11".repeat(20))) }.orEmpty()))
            setContent {
                DedunTheme {
                    ProvideSnackbarHost {
                        Box(modifier = Modifier.fillMaxSize()) {
                            GroupPayoutsScreen(
                                activeUserPublicKey = ME, chatRoomId = room.chatRoom.id, chain = chain, initialUIState = state,
                                chatRepository = ChatRepository.NO_OP_CHAT_REPOSITORY, frostSigningRepository = FrostSigningRepository.NO_OP_FROST_SIGNING_REPOSITORY,
                                payoutRepository = PayoutRepository.NO_OP, payoutDestinationRepository = destinations, wallets = null,
                                onNavigateBack = {},
                            )
                        }
                    }
                }
            }
            assertions()
        }

    // ---- the wallet screen ----

    private fun opened(rows: List<GroupWavelengthOnchainPayout>) =
        GroupWavelengthWalletUIState.Opened(room, wallet, GroupWavelengthUiFixtures.operator(OTHER), true, false, true, userPublicKey = ME).copy(onchainPayouts = rows)

    private fun row(leftSat: Long? = null, confirmed: Boolean = false, expiresAt: Long = 4_000_000_000) = GroupWavelengthOnchainPayout(
        GroupWavelengthOnchainPayoutEvent.Payout(chain, 0, OTHER, "d", address, 8_000, 2_000, expiresAt, 1), leftSat, confirmed,
    )

    @Test
    fun `an onchain payout shows where this device has seen it get to`() = runDesktopComposeUiTest(width = 400, height = 2400) {
        setContent {
            DedunTheme {
                ProvideSnackbarHost {
                    GroupWavelengthWalletContent(Modifier, opened(listOf(row(leftSat = 8_400, confirmed = true))), chain)
                }
            }
        }
        onNodeWithText("Paid, with 400 sats in fees").assertIsDisplayed()
    }

    @Test
    fun `waiting, in a round, and run out are each said`() {
        listOf(
            row() to "Approved. Waiting for whoever runs the wallet",
            row(leftSat = 8_400) to "In a round. Paid once it confirms",
            row(expiresAt = 2) to "No round paid it before the approval ran out. Nothing left",
        ).forEach { (row, says) ->
            runDesktopComposeUiTest(width = 400, height = 2400) {
                setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthWalletContent(Modifier, opened(listOf(row)), chain) } } }
                onNodeWithText(says).assertIsDisplayed()
            }
        }
    }
}
