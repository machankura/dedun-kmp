package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.view.model.WavelengthWalletUIState
import com.machankura.dedun.compose.wallet.wavelength.WalletRecovery
import com.machankura.dedun.compose.wallet.wavelength.WalletUnavailability
import engineering.lightning.wavelength.Network
import engineering.lightning.wavelength.model.Balance
import engineering.lightning.wavelength.model.Entry
import engineering.lightning.wavelength.model.EntryKind
import engineering.lightning.wavelength.model.EntryStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/** The profile's wallet row and the wallet screen, drawn for each state -- `docs/wavelength-wallet.md`, Phase 5. */
@OptIn(ExperimentalTestApi::class)
class WavelengthWalletScreenJvmTest {
    private val taps = mutableListOf<String>()

    private val loaded = WavelengthWalletUIState.Loaded(
        network = Network.SIGNET,
        balance = Balance(confirmedSat = 21_000, pendingInSat = 500),
        entries = listOf(
            Entry(id = "b", kind = EntryKind.SEND, status = EntryStatus.PENDING, amountSat = 2_000, feeSat = 3),
            Entry(id = "a", kind = EntryKind.RECEIVE, status = EntryStatus.COMPLETE, amountSat = 23_000),
        ),
        syncing = false,
        recovery = null,
        server = null,
    )

    @Composable
    private fun Row(state: WavelengthWalletUIState) {
        DedunTheme {
            LazyColumn { wavelengthWalletItems(state, onOpen = { taps += "open" }, onTurnOn = { taps += "turn on" }) }
        }
    }

    @Composable
    private fun Content(state: WavelengthWalletUIState) {
        DedunTheme {
            ProvideSnackbarHost {
                WavelengthWalletContent(state, onOpenEntry = { taps += "entry $it" }, onRetry = { taps += "retry" }, onTurnOn = { taps += "turn on" })
            }
        }
    }

    @Test
    fun `the row shows the balance and what is pending, and opens the wallet`() = runDesktopComposeUiTest(400, 600) {
        setContent { Row(loaded) }
        onNodeWithText("Pending: 500 sats", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("21,000 sats", useUnmergedTree = true).performClick()
        assertEquals(listOf("open"), taps)
    }

    @Test
    fun `a wallet not turned on offers to turn it on, and one behind Tor says why and does nothing`() = runDesktopComposeUiTest(400, 600) {
        val state = mutableStateOf<WavelengthWalletUIState>(WavelengthWalletUIState.Unavailable(WalletUnavailability.NotConsented))
        setContent { Row(state.value) }
        onNodeWithText("Turn on", useUnmergedTree = true).performClick()
        assertEquals(listOf("turn on"), taps)

        taps.clear()
        state.value = WavelengthWalletUIState.Unavailable(WalletUnavailability.TorOn)
        waitForIdle()
        onNodeWithText("Not available with Tor on", useUnmergedTree = true).performClick()
        assertEquals(emptyList(), taps)
    }

    @Test
    fun `this device with no runtime says so`() = runDesktopComposeUiTest(400, 600) {
        setContent { Row(WavelengthWalletUIState.Unavailable(WalletUnavailability.UnsupportedPlatform)) }
        onNodeWithText("Not available on this device", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `the screen shows the balance, the test network and the history`() = runDesktopComposeUiTest(400, 800) {
        setContent { Content(loaded) }
        onNodeWithText("21,000 sats").assertIsDisplayed()
        onNodeWithText("A test wallet on signet: its coins have no value").assertIsDisplayed()
        onNodeWithText("−2,000 sats").assertIsDisplayed()
        onNodeWithText("+23,000 sats").assertIsDisplayed()
        onNodeWithText("Sent").performClick()
        assertEquals(listOf("entry b"), taps)
    }

    @Test
    fun `an invoice nobody has paid is not shown as money on its way`() = runDesktopComposeUiTest(400, 800) {
        val unpaid = Entry(id = "c", kind = EntryKind.RECEIVE, status = EntryStatus.PENDING, amountSat = 12_000)
        setContent { Content(loaded.copy(entries = listOf(unpaid))) }
        onNodeWithText("12,000 sats").assertIsDisplayed()
        onNodeWithText("+12,000 sats").assertDoesNotExist()
        onNodeWithText("Waiting for payment").assertIsDisplayed()
    }

    @Test
    fun `credit is shown apart and is not in the balance`() = runDesktopComposeUiTest(400, 800) {
        setContent { Content(loaded.copy(balance = Balance(confirmedSat = 21_000, creditAvailableSat = 700))) }
        onNodeWithText("21,000 sats").assertIsDisplayed()
        onNodeWithText("Credit: 700 sats").assertIsDisplayed()
    }

    @Test
    fun `a recovery that found coins says so, and one that found nothing says nothing`() = runDesktopComposeUiTest(400, 800) {
        val state = mutableStateOf<WavelengthWalletUIState>(loaded.copy(recovery = WalletRecovery(coins = 2, boardings = 1, receives = 0)))
        setContent { Content(state.value) }
        onNodeWithText("Restored 3 coins from your recovery phrase. Payment history before the restore is not restored.").assertIsDisplayed()
        state.value = loaded.copy(recovery = WalletRecovery(0, 0, 0))
        waitForIdle()
        onNodeWithText("Restored", substring = true).assertDoesNotExist()
    }

    @Test
    fun `an empty wallet has its own message`() = runDesktopComposeUiTest(400, 800) {
        setContent { Content(loaded.copy(entries = emptyList())) }
        onNodeWithText("No payments yet").assertIsDisplayed()
    }

    @Test
    fun `a failure retries, and a wrong identity does not`() = runDesktopComposeUiTest(400, 800) {
        val state = mutableStateOf<WavelengthWalletUIState>(WavelengthWalletUIState.Failed)
        setContent { Content(state.value) }
        onNodeWithText("Couldn't open the wallet").assertIsDisplayed()
        onNodeWithText("Try again").performClick()
        assertEquals(listOf("retry"), taps)

        state.value = WavelengthWalletUIState.WrongIdentity
        waitForIdle()
        onNodeWithText("This wallet's files belong to other keys. Nothing was changed.").assertIsDisplayed()
        onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun `two panes from the expanded breakpoint, with the payment beside the list`() = runDesktopComposeUiTest(1280, 800) {
        setContent { Content(loaded) }
        onNodeWithText("Select a payment to see its details").assertIsDisplayed()
        onNodeWithText("Received").performClick()
        waitForIdle()
        assertEquals(emptyList(), taps)
        onNodeWithText("23,000 sats").assertIsDisplayed()
    }
}
