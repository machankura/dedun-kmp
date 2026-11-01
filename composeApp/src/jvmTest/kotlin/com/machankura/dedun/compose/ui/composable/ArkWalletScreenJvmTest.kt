package com.machankura.dedun.compose.ui.composable

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.view.model.ArkWalletUIState
import com.machankura.dedun.compose.wallet.ark.ArkBalance
import com.machankura.dedun.compose.wallet.ark.ArkDirection
import com.machankura.dedun.compose.wallet.ark.ArkError
import com.machankura.dedun.compose.wallet.ark.ArkExpiry
import com.machankura.dedun.compose.wallet.ark.ArkMovement
import com.machankura.dedun.compose.wallet.ark.ArkMovementStatus
import com.machankura.dedun.compose.wallet.ark.ArkNetwork
import com.machankura.dedun.compose.wallet.ark.ArkRail
import com.machankura.dedun.compose.wallet.ark.ArkRecovery
import com.machankura.dedun.compose.wallet.ark.ArkUnavailability
import fr.acinq.bitcoin.Satoshi
import kotlin.test.Test
import kotlin.test.assertEquals

/** The profile's wallet row and the wallet screen, drawn for each state -- `docs/bark-wallet.md`, Phase 4. */
@OptIn(ExperimentalTestApi::class)
class ArkWalletScreenJvmTest {
    private val taps = mutableListOf<String>()

    private val loaded = ArkWalletUIState.Loaded(
        network = ArkNetwork.Signet,
        balance = ArkBalance(Satoshi(21_000), pendingInRound = Satoshi(500)),
        movements = listOf(
            ArkMovement(7, ArkMovementStatus.Pending, ArkDirection.Outgoing, ArkRail.Lightning, Satoshi(2_000), Satoshi(3), "lntbs1", null, null, null),
            ArkMovement(6, ArkMovementStatus.Succeeded, ArkDirection.Incoming, ArkRail.Ark, Satoshi(23_000), Satoshi(0), null, null, null, null),
        ),
        expiry = ArkExpiry.Fine(144),
        recovery = ArkRecovery.NotRun,
    )

    @Composable
    private fun Row(state: ArkWalletUIState) {
        DedunTheme {
            LazyColumn { arkWalletItems(state, onOpen = { taps += "open" }, onTurnOn = { taps += "turn on" }) }
        }
    }

    @Composable
    private fun Content(state: ArkWalletUIState) {
        DedunTheme {
            ProvideSnackbarHost {
                ArkWalletContent(state, onOpenMovement = { taps += "movement $it" }, onRetry = { taps += "retry" }, onTurnOn = { taps += "turn on" })
            }
        }
    }

    @Test
    fun `the row shows the balance and what is pending, and opens the wallet`() = runDesktopComposeUiTest(400, 600) {
        setContent { Row(loaded) }
        onNodeWithText("21,000 sats", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Pending: 500 sats", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("21,000 sats", useUnmergedTree = true).performClick()
        assertEquals(listOf("open"), taps)
    }

    @Test
    fun `a wallet not turned on offers to turn it on, and one behind Tor says why and does nothing`() = runDesktopComposeUiTest(400, 600) {
        val state = androidx.compose.runtime.mutableStateOf<ArkWalletUIState>(ArkWalletUIState.Unavailable(ArkUnavailability.NotConsented))
        setContent { Row(state.value) }
        onNodeWithText("Not turned on", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Turn on", useUnmergedTree = true).performClick()
        assertEquals(listOf("turn on"), taps)

        state.value = ArkWalletUIState.Unavailable(ArkUnavailability.TorOn)
        waitForIdle()
        onNodeWithText("Not available with Tor on", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Not available with Tor on", useUnmergedTree = true).performClick()
        assertEquals(listOf("turn on"), taps)
    }

    @Test
    fun `the screen lists the history newest first, signed, and opens a payment below the expanded breakpoint`() = runDesktopComposeUiTest(400, 900) {
        setContent { Content(loaded) }
        onNodeWithText("Sent over Lightning", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("−2,000 sats", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("+23,000 sats", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("A test wallet on signet: its coins have no value", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Sent over Lightning", useUnmergedTree = true).performClick()
        assertEquals(listOf("movement 7"), taps)
    }

    @Test
    fun `an empty wallet says there are no payments yet`() = runDesktopComposeUiTest(400, 900) {
        setContent { Content(loaded.copy(movements = emptyList())) }
        onNodeWithText("No payments yet", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a wallet that could not open offers a retry`() = runDesktopComposeUiTest(400, 900) {
        setContent { Content(ArkWalletUIState.Failed(ArkError.ServerUnreachable("no"), opened = false)) }
        onNodeWithText("Couldn't open the wallet", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a payment opens beside the list from the expanded breakpoint up`() = runDesktopComposeUiTest(1280, 900) {
        setContent { Content(loaded) }
        onNodeWithText("Select a payment to see its details.", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Received over Ark", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithText("Status", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(emptyList(), taps)
    }
}
