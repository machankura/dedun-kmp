package com.machankura.dedun.compose.ui.composable

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.Modifier
import com.machankura.dedun.compose.ui.composable.widgets.ProvideSnackbarHost
import com.machankura.dedun.compose.ui.theme.DedunTheme
import com.machankura.dedun.compose.ui.view.model.WavelengthReceiveUIState
import com.machankura.dedun.compose.ui.view.state.GroupWavelengthReceiveUIState
import com.machankura.dedun.compose.wallet.bip21Uri
import com.machankura.dedun.compose.wallet.wavelength.group.PaidRequest
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Satoshi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The guard for `docs/paid-requests.md`: a request that has been paid is not drawn as a QR code,
 * and the money is announced in its place.
 *
 * Asserted against what the screen actually draws rather than against the source, because
 * [com.machankura.dedun.compose.ui.composable.widgets.QRCodeView] gives its image the request as
 * a content description -- so "is this scannable?" is a question the semantics tree answers.
 * A new rail that forgets the rule fails here rather than passing a scan of its imports.
 *
 * Each screen's default rail is the one tested: the profile's own invoice, and the group's
 * boarding address. Between them that is both rails and two of the three one-shot screens; the
 * third, group Lightning, builds its own view model and has no content-only entry point, and is
 * covered by `GroupLightningWalletViewModelJvmTest` instead.
 */
@OptIn(ExperimentalTestApi::class)
class PaidRequestsNoQrJvmTest {

    private val invoice = "lnbcrt1250n1fakeinvoice"
    private val address = "bcrt1p" + "q".repeat(58)

    private fun wavelength(state: WavelengthReceiveUIState, assertions: androidx.compose.ui.test.ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = 400, height = 1600) {
            setContent {
                DedunTheme {
                    ProvideSnackbarHost {
                        WavelengthReceiveContent(
                            state = state,
                            modifier = Modifier,
                            onAmountChange = {},
                            onMakeInvoice = {},
                            onMakeAddress = {},
                        )
                    }
                }
            }
            assertions()
        }

    private fun group(state: GroupWavelengthReceiveUIState, assertions: androidx.compose.ui.test.ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = 400, height = 1600) {
            setContent {
                DedunTheme {
                    ProvideSnackbarHost {
                        GroupWavelengthReceiveContent(Modifier, state, Chain.Regtest)
                    }
                }
            }
            assertions()
        }

    @Test
    fun `an unpaid invoice is scannable`() = wavelength(
        WavelengthReceiveUIState(hasWallet = true, amount = "1250", invoice = invoice)
    ) {
        assertEquals(1, onAllNodesWithContentDescription(invoice).fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText("Payment received").fetchSemanticsNodes().size)
    }

    @Test
    fun `a paid invoice is not scannable, and says what arrived`() = wavelength(
        WavelengthReceiveUIState(hasWallet = true, amount = "1250", invoice = invoice, invoicePaid = Satoshi(1_250))
    ) {
        assertEquals(0, onAllNodesWithContentDescription(invoice).fetchSemanticsNodes().size, "a paid invoice must not be paid again")
        onNodeWithText("Payment received").assertIsDisplayed()
        onNodeWithText("1,250 sats").assertIsDisplayed()
        // Still readable, so a member can check it against what the payer says they paid.
        onNodeWithText(invoice).assertIsDisplayed()
    }

    @Test
    fun `an unpaid group address is scannable`() = group(
        GroupWavelengthReceiveUIState.Loaded(address = address, addressAsked = true)
    ) {
        assertEquals(1, onAllNodesWithContentDescription(bip21Uri(address)).fetchSemanticsNodes().size)
    }

    @Test
    fun `a paid group address is not scannable, and offers another`() = group(
        GroupWavelengthReceiveUIState.Loaded(
            address = address,
            addressAsked = true,
            addressPaid = PaidRequest(Satoshi(7_000), onTheOperatorsWord = false),
        )
    ) {
        assertEquals(0, onAllNodesWithContentDescription(bip21Uri(address)).fetchSemanticsNodes().size)
        onNodeWithText("Payment received").assertIsDisplayed()
        onNodeWithText("7,000 sats").assertIsDisplayed()
        onNodeWithText("Ask for another address").assertIsDisplayed()
        onNodeWithText(address).assertIsDisplayed()
    }

    /** Where only the operator says a payment happened, the panel names the operator (D4). */
    @Test
    fun `a payment only the operator claims says whose word it is`() = group(
        GroupWavelengthReceiveUIState.Loaded(
            invoice = invoice,
            invoiceAmountSat = 1_250,
            invoicePaid = PaidRequest(Satoshi(1_250), onTheOperatorsWord = true),
            operatorName = "zanele",
        )
    ) {
        // The screen opens on its onchain rail; the invoice is behind the Lightning chip.
        onNodeWithText("Lightning").performClick()
        assertEquals(0, onAllNodesWithContentDescription(invoice).fetchSemanticsNodes().size)
        onNodeWithText("Payment received").assertIsDisplayed()
        onNodeWithText("zanele says a payment arrived").assertIsDisplayed()
    }

    @Test
    fun `an unpaid group invoice is scannable`() = group(
        GroupWavelengthReceiveUIState.Loaded(invoice = invoice, invoiceAmountSat = 1_250)
    ) {
        onNodeWithText("Lightning").performClick()
        assertEquals(1, onAllNodesWithContentDescription(invoice).fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText("Payment received").fetchSemanticsNodes().size)
    }
}
