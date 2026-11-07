package com.machankura.dedun.compose.ui.composable

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.machankura.dedun.compose.managers.GroupWavelengthWalletManager
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
        onNodeWithText("Your own Wavelength wallet can't be open while this device runs the group's. Opening yours pauses the group's for as long as you're in it.").assertIsDisplayed()
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
}
