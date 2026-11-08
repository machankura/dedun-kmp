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
import com.machankura.dedun.compose.database.model.GroupArkLeave
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthOnchainPayoutEvent
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthHistory
import com.machankura.dedun.compose.repository.ChatRepository
import com.machankura.dedun.compose.repository.FrostSigningRepository
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthRollout
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

    // The wallet holds the balance and the operator's least change unless a test says otherwise, so the ceiling binds only where one asks it to.
    private fun loaded(
        balance: MemberBalances.MemberBalance,
        paysOnchain: Boolean = true,
        inWalletSat: Long = balance.wavelength.availableSat + GroupWavelengthRollout.MIN_CHANGE_SAT,
        paysLightning: Boolean = false,
    ) = GroupPayoutsUIState.Loaded(
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
        wavelengthInWalletSat = inWalletSat,
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
    fun `what the operator takes is any spend leaving none of the change it asks for, or all of it`() {
        // What failed on mainnet: Bob's 1,214 sats out of a wallet of 1,218, which would have left 12 sats of change.
        assertFalse(GroupWavelengthRollout.operatorTakes(inWalletSat = 1_218, spendSat = 1_214))
        assertTrue(GroupWavelengthRollout.operatorTakes(inWalletSat = 1_214, spendSat = 1_214), "no change at all is fine")
        assertTrue(GroupWavelengthRollout.operatorTakes(inWalletSat = 2_214, spendSat = 1_214), "and change of the operator's least")
        assertTrue(GroupWavelengthRollout.operatorTakes(inWalletSat = 1_000, spendSat = 1_214), "a wallet too small is the balance's to refuse")
    }

    @Test
    fun `a payout that used to be refused for leaving too little change is now offered for less`() {
        // Both of these were WavelengthOnchainOffer.LeavesTooLittle, telling whoever read the row to deposit more into a wallet
        // nobody could get money out of. The ceiling takes the offer down to a spend the operator accepts instead.
        val balance = balance(10_000)
        assertEquals(
            GroupPayoutsViewModel.WavelengthOnchainOffer.Offer(7_500, 2_000, address),
            viewModel(loaded(balance, inWalletSat = 10_500)).wavelengthOnchainOffer(balance),
            "9,500 spendable of a 10,500-sat wallet, less the round's 2,000",
        )
        assertEquals(
            GroupPayoutsViewModel.WavelengthOnchainOffer.Offer(8_000, 2_000, address),
            viewModel(loaded(balance, inWalletSat = 11_000)).wavelengthOnchainOffer(balance),
            "and a wallet with room for the change pays the whole balance, as before",
        )

        val bob = balance(1_214)
        assertIs<Pair<Long, Long>>(viewModel(loaded(bob, inWalletSat = 3_000, paysLightning = true)).wavelengthPayable(bob))
    }

    @Test
    fun `the room this came from is offered 3,017 sats over Lightning and 1,048 onchain`() {
        // @Bob, 4,036 sats of a 4,048-sat wallet. Both rails refused before: spending 4,036 leaves 12 sats of change and the
        // operator asks for 1,000. The ceiling is 3,048, and what's left in the wallet afterwards is exactly the operator's change.
        val bob = balance(4_036)
        val lightning = assertIs<Pair<Long, Long>>(viewModel(loaded(bob, inWalletSat = 4_048, paysLightning = true)).wavelengthPayable(bob))
        assertEquals(3_017_000L to 30_170L, lightning)
        assertTrue(GroupWavelengthRollout.operatorTakes(4_048, (lightning.first + lightning.second + 999) / 1000))

        val onchain = assertIs<GroupPayoutsViewModel.WavelengthOnchainOffer.Offer>(viewModel(loaded(bob, inWalletSat = 4_048)).wavelengthOnchainOffer(bob))
        assertEquals(GroupPayoutsViewModel.WavelengthOnchainOffer.Offer(1_048, 2_000, address), onchain)
        assertTrue(GroupWavelengthRollout.operatorTakes(4_048, onchain.amountSat + onchain.maxFeeSat))
    }

    @Test
    fun `the ceiling reaches what's proposed and not just what the row says`() {
        val bob = balance(4_036)
        val repository = Proposing()
        val model = viewModel(loaded(bob, inWalletSat = 4_048), repository)
        model.usage[aliceKey] = UnusedAddressCheck.Usage.Unused
        model.payOutWavelengthOnchain(aliceKey, {}, {})
        // The propose runs on Dispatchers.IO, so wait for it rather than racing it.
        val deadline = System.currentTimeMillis() + 5_000
        while (repository.proposed.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(listOf(1_048L to 2_000L), repository.proposed, "the whole balance less the fee, 2,036, would be refused by the daemon")
    }

    @Test
    fun `a member whose balance is the whole wallet is offered 1,000 sats less than they hold`() {
        // Deliberately different from before. Aiming at no change is payable only if the round costs the whole fee limit, so the
        // ceiling gives up the operator's change rather than offering an amount that works by luck (B2).
        val whole = balance(4_048)
        val offer = assertIs<GroupPayoutsViewModel.WavelengthOnchainOffer.Offer>(viewModel(loaded(whole, inWalletSat = 4_048)).wavelengthOnchainOffer(whole))
        assertEquals(1_048L, offer.amountSat)
        (0L..offer.maxFeeSat).forEach { feeSat ->
            assertTrue(GroupWavelengthRollout.operatorTakes(4_048, offer.amountSat + feeSat), "a fee of $feeSat")
        }
    }

    @Test
    fun `a wallet too small to keep the change and pay a round says so, and doesn't blame a balance`() {
        // The balance would carry a round on its own; the wallet can't spare it. Any deposit helps, so there's no figure to quote (B6).
        val balance = balance(3_000)
        assertEquals(GroupPayoutsViewModel.WavelengthOnchainOffer.WalletTooSmall, viewModel(loaded(balance, inWalletSat = 3_000)).wavelengthOnchainOffer(balance))
        // A balance that couldn't carry a round in any wallet is still the balance's own shortfall.
        val little = balance(2_500)
        assertEquals(GroupPayoutsViewModel.WavelengthOnchainOffer.TooLittle(500), viewModel(loaded(little, inWalletSat = 20_000)).wavelengthOnchainOffer(little))
    }

    @Test
    fun `the operator's own least change can't be paid out to anybody`() {
        // Where this note's work ends: a wallet holding 1,000 sats, 988 of them on a row, and no offer on either rail. Both bounds
        // bind here -- the wallet can spare nothing and 988 sats couldn't carry a round anyway -- and the balance's reason is the
        // one that names a figure somebody can act on. What says the 1,000 is the operator's and not theirs is the held-back line.
        val left = balance(988)
        assertNull(viewModel(loaded(left, inWalletSat = 1_000, paysLightning = true)).wavelengthPayable(left))
        assertEquals(
            GroupPayoutsViewModel.WavelengthOnchainOffer.TooLittle(3_000),
            viewModel(loaded(left, inWalletSat = 1_000)).wavelengthOnchainOffer(left),
        )
        assertEquals(988L, viewModel(loaded(left, inWalletSat = 1_000)).wavelengthHeldBackSat(left), "all of it stays, and the row has to say so")
    }

    @Test
    fun `the most the wallet can spend is what's in it less the operator's least change, and never more than the balance`() {
        // The room this came from: 4,036 sats of a 4,048-sat wallet, which can spend 3,048 and keep the operator's 1,000.
        assertEquals(3_048L, GroupWavelengthRollout.spendableSat(inWalletSat = 4_048, balanceSat = 4_036))
        assertEquals(3_048L, GroupWavelengthRollout.spendableSat(inWalletSat = 4_048, balanceSat = 4_048), "the whole wallet is one member's, and still 1,000 stays")
        assertEquals(3_048L, GroupWavelengthRollout.spendableSat(inWalletSat = 4_048, balanceSat = 5_000), "a balance over the wallet is the wallet's limit")
        assertEquals(9_500L, GroupWavelengthRollout.spendableSat(inWalletSat = 10_500, balanceSat = 10_000), "what used to be refused for leaving 500")
        assertEquals(10_000L, GroupWavelengthRollout.spendableSat(inWalletSat = 11_000, balanceSat = 10_000), "a balance that already left enough is untouched")
        assertEquals(0L, GroupWavelengthRollout.spendableSat(inWalletSat = 1_000, balanceSat = 988), "the operator's own change can't be paid out")
        assertEquals(0L, GroupWavelengthRollout.spendableSat(inWalletSat = 500, balanceSat = 400), "nor a wallet under it")
        assertEquals(0L, GroupWavelengthRollout.spendableSat(inWalletSat = 0, balanceSat = 0))
    }

    @Test
    fun `a spend at the ceiling leaves change the operator accepts, at every fee the round may cost`() {
        // The invariant the ceiling exists for. The fee is a limit and not a figure, so this holds for every fee up to it -- and
        // the fees that matter are the ones just under the limit, where aiming at no change lands in the band that's refused.
        listOf(0L, 500L, 2_000L).forEach { maxFeeSat ->
            for (inWalletSat in 0L..5_000L step 13) {
                listOf(0L, inWalletSat / 2, inWalletSat - 1, inWalletSat, inWalletSat + 1_000).filter { it >= 0 }.forEach { balanceSat ->
                    val amountSat = GroupWavelengthRollout.spendableSat(inWalletSat, balanceSat) - maxFeeSat
                    if (amountSat < GroupWavelengthRollout.MIN_ONCHAIN_PAYOUT_SAT) return@forEach // no offer is made at all
                    for (feeSat in 0L..maxFeeSat) {
                        assertTrue(
                            GroupWavelengthRollout.operatorTakes(inWalletSat, amountSat + feeSat),
                            "a wallet of $inWalletSat paying $amountSat of a $balanceSat balance, at a fee of $feeSat",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `aiming at no change is what the ceiling refuses to do, because a cheaper round lands in the band`() {
        // What the `change <= 0` branch does when a member's balance is the whole wallet: offer everything less the fee limit, which
        // is only payable if the round costs the whole limit. The ceiling gives up 1,000 sats rather than an amount that works by luck.
        val maxFeeSat = 2_000L
        val aimedAtNoChange = 3_000L - maxFeeSat
        assertTrue(GroupWavelengthRollout.operatorTakes(3_000, aimedAtNoChange + maxFeeSat), "which is why it looks fine")
        assertFalse(
            GroupWavelengthRollout.operatorTakes(3_000, aimedAtNoChange + maxFeeSat - 1),
            "but a round a single sat cheaper leaves 999 sats of change, and the operator refuses it",
        )

        // The ceiling's offer out of a wallet big enough to make one: 2,000 sats arriving, and every fee up to the limit leaves 1,000 or more.
        val atTheCeiling = GroupWavelengthRollout.spendableSat(inWalletSat = 5_000, balanceSat = 5_000) - maxFeeSat
        assertEquals(2_000L, atTheCeiling)
        (0L..maxFeeSat).forEach { feeSat ->
            assertTrue(GroupWavelengthRollout.operatorTakes(5_000, atTheCeiling + feeSat), "a fee of $feeSat")
        }
    }

    @Test
    fun `the Lightning payout that used to be refused for its change is offered for what the wallet can spend`() =
        render(balance(1_214), UnusedAddressCheck.Usage.Unused, inWalletSat = 1_218, paysLightning = true) {
            // 1,214 sats of a 1,218-sat wallet: the whole balance leaves 12 sats of change and was refused outright. 218 sats
            // is what the wallet can spend, and 208 of them arrive once the fee limit is set aside.
            onNodeWithText("Pays @Alice 208 sats", substring = true).assertIsDisplayed()
            onNodeWithText("Pay out over Lightning from the Wavelength wallet").assertIsDisplayed()
            onNodeWithText("would leave less change", substring = true).assertDoesNotExist()
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
    fun `the row says what the operator's change keeps of their balance, beside what it pays`() =
        render(balance(4_036), UnusedAddressCheck.Usage.Unused, inWalletSat = 4_048, paysLightning = true) {
            onNodeWithText("Pays @Alice 3,017 sats", substring = true).assertIsDisplayed()
            onNodeWithText("988 sats of @Alice's balance stays behind", substring = true).assertIsDisplayed()
            onNodeWithText("less than 1,000 sats of change", substring = true).assertIsDisplayed()
        }

    @Test
    fun `the same row at a wide window says the same thing`() =
        render(balance(4_036), UnusedAddressCheck.Usage.Unused, inWalletSat = 4_048, paysLightning = true, width = 1_280) {
            onNodeWithText("Pays @Alice 3,017 sats", substring = true).assertIsDisplayed()
            onNodeWithText("988 sats of @Alice's balance stays behind", substring = true).assertIsDisplayed()
        }

    @Test
    fun `a balance paid in full says nothing about anything staying behind`() =
        render(balance(10_000), UnusedAddressCheck.Usage.Unused, paysLightning = true) {
            onNodeWithText("stays behind", substring = true).assertDoesNotExist()
            onNodeWithText("can't leave the Wavelength wallet", substring = true).assertDoesNotExist()
        }

    @Test
    fun `where the operator's change is all that's left, the row says so and offers no button`() =
        render(balance(988), UnusedAddressCheck.Usage.Unused, inWalletSat = 1_000, paysLightning = true) {
            onNodeWithText("988 sats of @Alice's balance can't leave the Wavelength wallet", substring = true).assertIsDisplayed()
            onNodeWithText("Only emptying the wallet moves them", substring = true).assertIsDisplayed()
            onNodeWithText("Pay out over Lightning from the Wavelength wallet").assertDoesNotExist()
            onNodeWithText("Pay out onchain from the Wavelength wallet").assertDoesNotExist()
            onNodeWithText("stays behind", substring = true).assertDoesNotExist()
        }

    @Test
    fun `a wallet that can't spare a round's fee says it's the wallet, not the balance`() =
        render(balance(3_000), UnusedAddressCheck.Usage.Unused, inWalletSat = 3_000) {
            onNodeWithText("The Wavelength wallet can't both pay for a round", substring = true).assertIsDisplayed()
            onNodeWithText("Any deposit into it helps", substring = true).assertIsDisplayed()
        }

    @Test
    fun `a payout waiting on either rail holds the Lightning one back too`() {
        val waiting = balance(5_000, openSat = 5_000)
        assertNull(viewModel(loaded(waiting, paysLightning = true)).wavelengthPayable(waiting))
        val free = balance(5_000)
        assertIs<Pair<Long, Long>>(viewModel(loaded(free, inWalletSat = 6_000, paysLightning = true)).wavelengthPayable(free))
    }

    private fun render(
        balance: MemberBalances.MemberBalance,
        usage: UnusedAddressCheck.Usage,
        inWalletSat: Long = balance.wavelength.availableSat + GroupWavelengthRollout.MIN_CHANGE_SAT,
        paysLightning: Boolean = false,
        width: Int = 400,
        assertions: ComposeUiTest.() -> Unit,
    ) =
        runDesktopComposeUiTest(width = width, height = 2000) {
            val destinations = object : PayoutDestinationRepository by PayoutDestinationRepository.NO_OP {
                override suspend fun usageOf(chatRoomId: String, chain: Chain, script: ByteVector) = usage
            }
            val state = loaded(balance, inWalletSat = inWalletSat, paysLightning = paysLightning).copy(context = PayoutRule.Context(CoinOwnership.Records.NONE, balance.destination?.onchainAddress?.let { mapOf(aliceKey to ByteVector("0014" + "11".repeat(20))) }.orEmpty()))
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

    // The screen reads one history now, built from the same rows a device holds (T1), so these go in
    // as the approval and the leave rather than as a row made by hand.
    private fun opened(rows: GroupWavelengthHistory.Rows) =
        GroupWavelengthWalletUIState.Opened(room, wallet, GroupWavelengthUiFixtures.operator(OTHER), true, false, true, userPublicKey = ME)
            .copy(entries = GroupWavelengthHistory.of(rows, nowSeconds = 1_800_000_000))

    private fun row(leftSat: Long? = null, confirmed: Boolean = false, expiresAt: Long = 4_000_000_000): GroupWavelengthHistory.Rows {
        val payout = GroupWavelengthOnchainPayoutEvent.Payout(chain, 0, OTHER, "d", address, 8_000, 2_000, expiresAt, 1)
        return GroupWavelengthHistory.Rows(
            leaves = leftSat?.let { listOf(GroupArkLeave("w", payout.id, "round", "00", 8_000, it - 8_000, confirmed)) }.orEmpty(),
            onchainPayouts = listOf(payout),
        )
    }

    @Test
    fun `an onchain payout shows where this device has seen it get to`() = runDesktopComposeUiTest(width = 400, height = 2400) {
        setContent {
            DedunTheme {
                ProvideSnackbarHost {
                    GroupWavelengthWalletContent(Modifier, opened(row(leftSat = 8_400, confirmed = true)), chain)
                }
            }
        }
        onNodeWithText("Paid, with 400 sats in fees", substring = true).assertIsDisplayed()
    }

    @Test
    fun `waiting, in a round, and run out are each said`() {
        listOf(
            row() to "Approved. Waiting for whoever runs the wallet",
            row(leftSat = 8_400) to "In a round. Paid once it confirms",
            row(expiresAt = 2) to "No round paid it before the approval ran out. Nothing left",
        ).forEach { (row, says) ->
            runDesktopComposeUiTest(width = 400, height = 2400) {
                setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthWalletContent(Modifier, opened(row), chain) } } }
                onNodeWithText(says, substring = true).assertIsDisplayed()
            }
        }
    }
}
