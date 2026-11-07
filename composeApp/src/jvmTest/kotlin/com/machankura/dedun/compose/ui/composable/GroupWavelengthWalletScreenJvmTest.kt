package com.machankura.dedun.compose.ui.composable

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.machankura.dedun.compose.database.model.GroupArkVtxo
import com.machankura.dedun.compose.managers.GroupWavelengthReports
import com.machankura.dedun.compose.managers.GroupWavelengthWalletManager
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthPaymentEvent
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthSnapshot
import com.machankura.dedun.compose.repository.GroupWavelengthExitPlan
import com.machankura.dedun.compose.ui.view.state.GroupWavelengthReceiveUIState
import com.machankura.dedun.compose.wallet.wavelength.WalletExpiry
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthTerms
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthLedger
import com.machankura.dedun.compose.ui.GroupWavelengthUiFixtures
import com.machankura.dedun.compose.ui.GroupWavelengthUiFixtures.ME
import com.machankura.dedun.compose.ui.GroupWavelengthUiFixtures.OTHER
import com.machankura.dedun.compose.ui.GroupWavelengthUiFixtures.key
import com.machankura.dedun.compose.ui.GroupWavelengthUiFixtures.localChatRoom
import com.machankura.dedun.compose.ui.GroupWavelengthUiFixtures.wallet
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.view.state.GroupWavelengthWalletUIState
import fr.acinq.bitcoin.Chain
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Wavelength wallet screen in each of its states before it holds anything, at phone width and in a wide window. */
@OptIn(ExperimentalTestApi::class)
class GroupWavelengthWalletScreenJvmTest {
    private val phone = 400
    private val wide = 1280

    private fun opened(chain: Chain = Chain.Signet, isParticipant: Boolean = true, isOperator: Boolean = false, canRunHere: Boolean = true, operator: String? = OTHER) =
        GroupWavelengthWalletUIState.Opened(localChatRoom, wallet(chain), operator?.let { GroupWavelengthUiFixtures.operator(it) }, isParticipant, isOperator, canRunHere, userPublicKey = ME)

    @Test
    fun `the row for each network says which it is, and a test network that its coins have no value`() = runDesktopComposeUiTest(phone, 800) {
        var opened = 0
        setContent { DedunTheme { WavelengthWalletRow(Chain.Signet) { opened++ } } }
        onNodeWithText("A test wallet on signet: its coins have no value").assertIsDisplayed()
        onNodeWithText("Wavelength wallet").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `on mainnet the row just names it`() = runDesktopComposeUiTest(phone, 800) {
        setContent { DedunTheme { WavelengthWalletRow(Chain.Mainnet) {} } }
        onNodeWithText("On mainnet").assertIsDisplayed()
    }

    @Test
    fun `no onchain wallet leads to the onchain wallet`() = runDesktopComposeUiTest(phone, 800) {
        var opened = 0
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthWalletContent(Modifier, GroupWavelengthWalletUIState.NeedsOnchainWallet(localChatRoom), Chain.Signet, onOpenOnchainWallet = { opened++ }) } } }
        onNodeWithText("Group onchain wallet").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `no key says what to do, and a group too small is told why`() = runDesktopComposeUiTest(phone, 800) {
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthWalletContent(Modifier, GroupWavelengthWalletUIState.NeedsKey(localChatRoom, null, canMakeKey = false), Chain.Signet) } } }
        onNodeWithText("A Wavelength wallet needs at least four members who hold the onchain wallet's key.").assertIsDisplayed()
    }

    private fun SemanticsNodeInteractionsProvider.assertCommitmentSaid() {
        onNodeWithText("This wallet needs 3 of your 4 devices online every few days, or the money stops being safe.").assertIsDisplayed()
        onNodeWithText("Whoever runs it needs an Android phone left on and connected. The group can change who that is at any time.").assertIsDisplayed()
        onNodeWithText("The way this key works hasn't been reviewed by anyone outside the project. Keep only what the group could afford to lose in it.").assertIsDisplayed()
    }

    @Test
    fun `before opening a wallet a group is told what it's agreeing to`() = runDesktopComposeUiTest(phone, 1200) {
        var opened = 0
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthWalletContent(Modifier, GroupWavelengthWalletUIState.NotOpened(localChatRoom, key, null, null), Chain.Signet, onOpenWallet = { opened++ }) } } }
        assertCommitmentSaid()
        onNodeWithText("A test wallet on signet: its coins have no value").assertIsDisplayed()
        onNodeWithText("Open a Wavelength wallet").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `in a wide window too`() = runDesktopComposeUiTest(wide, 900) {
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthWalletContent(Modifier, GroupWavelengthWalletUIState.NotOpened(localChatRoom, key, null, null), Chain.Signet) } } }
        assertCommitmentSaid()
    }

    @Test
    fun `on mainnet it says the cap, and no test network`() = runDesktopComposeUiTest(phone, 1200) {
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthWalletContent(Modifier, GroupWavelengthWalletUIState.NotOpened(localChatRoom, key, null, null), Chain.Mainnet) } } }
        onNodeWithText("On mainnet this wallet holds at most 100,000 sats, and every member's device holds it to that.").assertIsDisplayed()
    }

    @Test
    fun `a proposal already out is led to, and terms that have moved stop the button`() = runDesktopComposeUiTest(phone, 1200) {
        var state: GroupWavelengthWalletUIState by androidx.compose.runtime.mutableStateOf(GroupWavelengthWalletUIState.NotOpened(localChatRoom, key, "session", null))
        var session: String? = null
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthWalletContent(Modifier, state, Chain.Signet, onOpenSession = { session = it }) } } }
        onNodeWithText("See the proposal").performClick()
        assertEquals("session", session)
        state = GroupWavelengthWalletUIState.NotOpened(localChatRoom, key, null, GroupWavelengthWalletUIState.Blocked.TERMS_CHANGED)
        waitForIdle()
        onNodeWithText("The Ark operator's terms have changed since this version of Dedun was made, so a wallet opened now wouldn't work. Update Dedun first.").assertIsDisplayed()
        assertEquals(0, onAllNodes(androidx.compose.ui.test.hasText("Open a Wavelength wallet")).fetchSemanticsNodes().size)
    }

    @Test
    fun `a member waiting for the seed is told that, and a refusal offers nothing`() = runDesktopComposeUiTest(phone, 800) {
        var state: GroupWavelengthWalletUIState by androidx.compose.runtime.mutableStateOf(GroupWavelengthWalletUIState.WaitingForSeed(localChatRoom))
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthWalletContent(Modifier, state, Chain.Signet) } } }
        onNodeWithText("The group has opened a wallet, and this device is still waiting for its part of it. It will arrive when the member who opened it is next online.").assertIsDisplayed()
        state = GroupWavelengthWalletUIState.Refused(localChatRoom, GroupWavelengthWalletManager.Refusal.KEYS_DISAGREE)
        waitForIdle()
        onNodeWithText("The group opened a wallet that doesn't match the keys this device holds. It won't sign for it.").assertIsDisplayed()
    }

    @Test
    fun `a signer who could run it is offered that, with what it does to their own wallet`() = runDesktopComposeUiTest(phone, 1200) {
        var nominated = 0
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthWalletContent(Modifier, opened(), Chain.Signet, onNominate = { nominated++ }) } } }
        onNodeWithText("Your own Wavelength wallet can't be open while this device runs the group's. Opening yours pauses the group's for as long as you're in it, and yours only renews while it's open, so open it every few days.").assertIsDisplayed()
        onNodeWithText("Run this wallet on my device").performClick()
        assertEquals(1, nominated)
    }

    @Test
    fun `whoever runs it can step down`() = runDesktopComposeUiTest(phone, 1200) {
        var steppedDown = 0
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthWalletContent(Modifier, opened(isOperator = true, operator = ME), Chain.Signet, onStepDown = { steppedDown++ }) } } }
        onNodeWithText("Run by You, term 3", ignoreCase = true).assertIsDisplayed()
        onNodeWithText("Step down").performClick()
        assertEquals(1, steppedDown)
    }

    @Test
    fun `a device with no daemon can't be offered it, and says to stay open`() = runDesktopComposeUiTest(phone, 1200) {
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthWalletContent(Modifier, opened(canRunHere = false), Chain.Signet) } } }
        onNodeWithText("This device can sign for the wallet, but only an Android phone can run it.").assertIsDisplayed()
        onNodeWithText("Leave Dedun open on this device: it only signs for the wallet while it's running.").assertIsDisplayed()
        assertEquals(0, onAllNodes(androidx.compose.ui.test.hasText("Run this wallet on my device")).fetchSemanticsNodes().size)
    }

    @Test
    fun `a member who doesn't hold a share is offered nothing to do`() = runDesktopComposeUiTest(phone, 1200) {
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthWalletContent(Modifier, opened(isParticipant = false), Chain.Signet) } } }
        assertEquals(0, onAllNodes(androidx.compose.ui.test.hasText("Run this wallet on my device")).fetchSemanticsNodes().size)
    }

    private fun money(snapshot: GroupWavelengthSnapshot?, coins: List<GroupArkVtxo>, expiry: WalletExpiry = WalletExpiry.Nothing) = opened().copy(
        coins = coins,
        ledger = GroupWavelengthLedger.of(coins),
        snapshot = snapshot,
        comparison = snapshot?.let { GroupWavelengthReports.compare(it, coins) },
        expiry = expiry,
    )

    private val settledCoin = GroupArkVtxo("w", "a:0", 10_000, settled = true)
    private fun said(vararg sats: Long) = GroupWavelengthSnapshot("w", 3, 100, 500, sats.sum(), coins = sats.mapIndexed { i, sat -> GroupWavelengthSnapshot.Coin(if (i == 0) "a:0" else "b$i:0", sat, "live") })

    @Test
    fun `the balance is what this device signed for, with the operator's figure beside it`() = runDesktopComposeUiTest(phone, 1400) {
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthWalletContent(Modifier, money(said(10_000), listOf(settledCoin)), Chain.Signet) } } }
        onNodeWithText("10,000 sats").assertIsDisplayed()
        onNodeWithText("The operator says 10,000 sats, as of its last report").assertIsDisplayed()
        onNodeWithText("Matches what your device signed").assertIsDisplayed()
    }

    @Test
    fun `an operator that lies is shown differing, and doesn't change the headline`() = runDesktopComposeUiTest(phone, 1400) {
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthWalletContent(Modifier, money(said(10_000, 90_000), listOf(settledCoin)), Chain.Signet) } } }
        onNodeWithText("10,000 sats").assertIsDisplayed()
        onNodeWithText("The operator says 100,000 sats, as of its last report").assertIsDisplayed()
        onNodeWithText("Differs from what your device signed").assertIsDisplayed()
    }

    @Test
    fun `a restored phone says it can't confirm, rather than that the wallet is empty`() = runDesktopComposeUiTest(phone, 1400) {
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthWalletContent(Modifier, money(said(10_000), emptyList()), Chain.Signet) } } }
        onNodeWithText("Your device hasn't signed for any coins of this wallet yet.").assertIsDisplayed()
        onNodeWithText("Your device has no record of these coins, so it can't confirm this. That's expected on a restored or new phone.").assertIsDisplayed()
    }

    @Test
    fun `coins near their deadline say so, and how long`() = runDesktopComposeUiTest(phone, 1400) {
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthWalletContent(Modifier, money(null, listOf(settledCoin), WalletExpiry.Urgent(144)), Chain.Signet) } } }
        onNodeWithText("Coins need renewing within", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a refusal is on the screen, and too few signers seen says a round may not work`() = runDesktopComposeUiTest(phone, 1600) {
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthWalletContent(Modifier, opened().copy(alarms = listOf("Your device refused to co-sign for the Wavelength wallet: a transfer to a script nobody authorised.")), Chain.Signet) } } }
        onNodeWithText("Your device refused something").assertIsDisplayed()
        onNodeWithText("Your device refused to co-sign for the Wavelength wallet: a transfer to a script nobody authorised.").assertIsDisplayed()
        onNodeWithText("Only 0 of the 3 devices a round needs have answered in the last hour, so a round may not work.").assertIsDisplayed()
    }

    @Test
    fun `in a wide window the money and the group are side by side, and both are there`() = runDesktopComposeUiTest(wide, 1200) {
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthWalletContent(Modifier, money(said(10_000), listOf(settledCoin)), Chain.Signet) } } }
        onNodeWithText("10,000 sats").assertIsDisplayed()
        onNodeWithText("Signers").assertIsDisplayed()
    }

    @Test
    fun `advanced names what a device holds in detail, and what nobody has tried`() = runDesktopComposeUiTest(phone, 1800) {
        val coins = listOf(settledCoin.copy(expiresAtHeight = 2_000), GroupArkVtxo("w", "b:0", 500), GroupArkVtxo("w", "c:0", 700, settled = true, reservedBy = "r"))
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthAdvancedContent(Modifier, money(said(10_000, 5), coins)) } } }
        onNodeWithText("VTXOs on this device").assertIsDisplayed()
        onNodeWithText("Settled. Can be swept from block 2,000").assertIsDisplayed()
        onNodeWithText("Waiting to see its round finish").assertIsDisplayed()
        onNodeWithText("Held for something signed").assertIsDisplayed()
        onNodeWithText("The operator reports", substring = true).assertIsDisplayed()
        onNodeWithText("A unilateral exit, which leaves without the Ark operator's help, has never been run on any network.").assertIsDisplayed()
    }

    @Test
    fun `an address says it was checked, what it takes and what it costs`() = runDesktopComposeUiTest(phone, 1400) {
        setContent {
            DedunTheme {
                ProvideSnackbarHost {
                    GroupWavelengthReceiveContent(Modifier, GroupWavelengthReceiveUIState.Loaded(address = "bc1paddress", terms = GroupWavelengthTerms.of(Chain.Mainnet), maxRoundFeeSat = 2_000, roomLeftSat = 60_000), Chain.Mainnet)
                }
            }
        }
        onNodeWithText("bc1paddress").assertIsDisplayed()
        onNodeWithText("Your device checked that this address is one the group's key makes. Whatever's sent here counts as yours.").assertIsDisplayed()
        onNodeWithText("Coins join the wallet in a round, once they have 6 confirmations and enough of the group's devices are online.").assertIsDisplayed()
        onNodeWithText("Send at least 1,000 sats, and no more than 50,000 to a coin.").assertIsDisplayed()
        onNodeWithText("The wallet can take 60,000 sats more on mainnet, and a coin that would pass that won't be signed for.").assertIsDisplayed()
        onNodeWithText("A round takes its fee out of what's sent: the group lets it take up to 2,000 sats.").assertIsDisplayed()
    }

    @Test
    fun `someone who hasn't started depositing is told why not, and a full wallet says so`() = runDesktopComposeUiTest(phone, 800) {
        var state: GroupWavelengthReceiveUIState by androidx.compose.runtime.mutableStateOf(GroupWavelengthReceiveUIState.NotADepositor)
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthReceiveContent(Modifier, state, Chain.Mainnet) } } }
        onNodeWithText("Only a member who has started depositing", substring = true).assertIsDisplayed()
        state = GroupWavelengthReceiveUIState.AtTheCap
        waitForIdle()
        onNodeWithText("This wallet is at the most it can hold on mainnet", substring = true).assertIsDisplayed()
    }

    @Test
    fun `asking gets an address, from the wallet's screen too`() = runDesktopComposeUiTest(phone, 1400) {
        var asked = 0
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthReceiveContent(Modifier, GroupWavelengthReceiveUIState.Loaded(), Chain.Mainnet, onAsk = { asked++ }) } } }
        onNodeWithText("Get an address").performClick()
        assertEquals(1, asked)
    }

    private fun payout() = GroupWavelengthPaymentEvent.Payment(Chain.Signet, 0, "lntbs1000n1invalid", 50, 500, 4_000_000_000, GroupWavelengthPaymentEvent.Payout(OTHER, "d"))

    @Test
    fun `on mainnet there's no payout over Lightning, and it says why and what to do instead`() = runDesktopComposeUiTest(phone, 1400) {
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthWalletContent(Modifier, opened(chain = Chain.Mainnet), Chain.Mainnet) } } }
        onNodeWithText("Payouts over Lightning stay off on mainnet until a failed payment's refund has been shown to work. Leaving moves the money to the onchain wallet, which pays out.").assertIsDisplayed()
        assertEquals(0, onAllNodes(androidx.compose.ui.test.hasText("Pay out")).fetchSemanticsNodes().size)
    }

    @Test
    fun `on signet a payout can be asked for`() = runDesktopComposeUiTest(phone, 1400) {
        var paying = 0
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthWalletContent(Modifier, opened(), Chain.Signet, onPayOut = { paying++ }) } } }
        onNodeWithText("Pay out").performClick()
        assertEquals(1, paying)
    }

    @Test
    fun `an approved payout shows where it's got to`() = runDesktopComposeUiTest(phone, 1600) {
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthWalletContent(Modifier, opened().copy(payouts = listOf(payout())), Chain.Signet) } } }
        onNodeWithText("Approved. Waiting for whoever runs the wallet").assertIsDisplayed()
    }

    @Test
    fun `leaving asks first, says what it does, and goes to the group`() = runDesktopComposeUiTest(phone, 1600) {
        var asked = 0
        val coins = listOf(settledCoin)
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthWalletContent(Modifier, money(null, coins), Chain.Mainnet, onLeave = { asked++ }) } } }
        onNodeWithText("Leave").performClick()
        onNodeWithText("Leave for the onchain wallet?").assertIsDisplayed()
        assertEquals(0, asked)
        onNodeWithText("Ask the group").performClick()
        assertEquals(1, asked)
    }

    @Test
    fun `once asked, it says so rather than offering again`() = runDesktopComposeUiTest(phone, 1600) {
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthWalletContent(Modifier, money(null, listOf(settledCoin)).copy(leaveAsked = true), Chain.Mainnet) } } }
        onNodeWithText("The group has asked to leave", substring = true).assertIsDisplayed()
        assertEquals(0, onAllNodes(androidx.compose.ui.test.hasText("Leave")).fetchSemanticsNodes().size)
    }

    @Test
    fun `with coins near their deadline, another signer can take over`() = runDesktopComposeUiTest(phone, 1600) {
        var nominated = 0
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthWalletContent(Modifier, money(null, listOf(settledCoin), WalletExpiry.Urgent(100)), Chain.Signet, onNominate = { nominated++ }) } } }
        onNodeWithText("If whoever runs it has gone, this device can take over", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a unilateral exit says first that it's never been run, and asks twice`() = runDesktopComposeUiTest(phone, 1400) {
        var started = 0
        val plan = GroupWavelengthExitPlan(listOf("a:0"), canStart = true, fundingShortfallSat = 0, underWay = emptyList())
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthExitContent(Modifier, plan, onStart = { started++ }) } } }
        onNodeWithText("This has never been run on any network", substring = true).assertIsDisplayed()
        onNodeWithText("Start leaving").performClick()
        assertEquals(0, started)
        onNodeWithText("It can't be undone, and nobody has run it before.").assertIsDisplayed()
    }

    @Test
    fun `a unilateral exit that can't start says what it's short of, and any other device isn't offered it`() = runDesktopComposeUiTest(phone, 1400) {
        var plan: GroupWavelengthExitPlan? by androidx.compose.runtime.mutableStateOf(GroupWavelengthExitPlan(listOf("a:0"), canStart = false, fundingShortfallSat = 2_000, underWay = emptyList()))
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthExitContent(Modifier, plan) } } }
        onNodeWithText("The wallet's onchain account needs 2,000 sats more for the fees before this can start.").assertIsDisplayed()
        plan = null
        waitForIdle()
        onNodeWithText("Only the device that runs this wallet can do this.").assertIsDisplayed()
    }

    @Test
    fun `someone who left is said, with what to do about it, and the old wallet can be left`() = runDesktopComposeUiTest(phone, 2000) {
        var made = 0
        var left = 0
        var state: GroupWavelengthWalletUIState by androidx.compose.runtime.mutableStateOf(opened().copy(departed = listOf("f".repeat(64))))
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthWalletContent(Modifier, state, Chain.Signet, onOpenCeremony = { made++ }, onLeaveEarlier = { left++ }) } } }
        onNodeWithText("1 people who left the group still hold a share", substring = true).assertIsDisplayed()
        onNodeWithText("Make a key").performClick()
        assertEquals(1, made)
        state = opened().copy(earlier = listOf(GroupWavelengthWalletUIState.Earlier(wallet(), null, 5_000, leaveAsked = false)))
        waitForIdle()
        onNodeWithText("Your device signed for 5,000 sats still in it", substring = true).assertIsDisplayed()
        onNodeWithText("Leave this wallet").performClick()
        assertEquals(1, left)
    }

    @Test
    fun `with a key ready, it offers the new wallet`() = runDesktopComposeUiTest(phone, 1600) {
        var opened = 0
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthWalletContent(Modifier, opened().copy(departed = listOf("f".repeat(64)), canOpenNew = true), Chain.Signet, onOpenWallet = { opened++ }) } } }
        onNodeWithText("Open a new Wavelength wallet").performClick()
        assertEquals(1, opened)
    }

    private val lightningTerms = GroupWavelengthTerms.of(Chain.Mainnet)

    @Test
    fun `Lightning says first that the operator makes the invoice, and asks for an amount`() = runDesktopComposeUiTest(phone, 1400) {
        var asked: Long? = null
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthReceiveContent(Modifier, GroupWavelengthReceiveUIState.Loaded(terms = lightningTerms, roomLeftSat = 30_000), Chain.Mainnet, onAskInvoice = { asked = it }) } } }
        onNodeWithText("Lightning").performClick()
        onNodeWithText("Whoever runs the wallet makes this invoice, and your device can't confirm where it pays. Only use it if you trust them with this amount.").assertIsDisplayed()
        onNodeWithText("At least 1,000 sats, and no more than 30,000.").assertIsDisplayed()
        onNodeWithText("Amount in sats").performTextInput("2500")
        onNodeWithText("Get an invoice").performClick()
        assertEquals(2_500L, asked)
    }

    @Test
    fun `an invoice is shown with what was checked of it, and too few devices is said`() = runDesktopComposeUiTest(phone, 1600) {
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthReceiveContent(Modifier, GroupWavelengthReceiveUIState.Loaded(invoice = "lnbc1theinvoice", invoiceAmountSat = 2_500, terms = lightningTerms, enoughSeen = false), Chain.Mainnet) } } }
        onNodeWithText("Lightning").performClick()
        onNodeWithText("lnbc1theinvoice").assertIsDisplayed()
        onNodeWithText("Your device checked that this invoice is for 2,500 sats. What's paid counts as yours once the group has claimed it.").assertIsDisplayed()
        onNodeWithText("Enough of the group's devices have to be online to claim a payment", substring = true).assertIsDisplayed()
        onNodeWithText("Get another invoice").assertIsDisplayed()
    }

    @Test
    fun `asking for an invoice says it's waiting on whoever runs the wallet`() = runDesktopComposeUiTest(phone, 1200) {
        setContent { DedunTheme { ProvideSnackbarHost { GroupWavelengthReceiveContent(Modifier, GroupWavelengthReceiveUIState.Loaded(invoiceAsked = true, terms = lightningTerms), Chain.Mainnet) } } }
        onNodeWithText("Lightning").performClick()
        onNodeWithText("Asked. The invoice comes from whoever runs the wallet", substring = true).assertIsDisplayed()
        assertEquals(0, onAllNodes(androidx.compose.ui.test.hasText("Get an invoice")).fetchSemanticsNodes().size)
    }
}
