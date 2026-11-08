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
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.PrivateKey
import fr.acinq.bitcoin.utils.Either
import fr.acinq.lightning.CltvExpiryDelta
import fr.acinq.lightning.Feature
import fr.acinq.lightning.FeatureSupport
import fr.acinq.lightning.Features
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.utils.msat
import fr.acinq.secp256k1.Hex
import kotlin.time.Clock
import com.machankura.dedun.compose.wallet.wavelength.group.PaidRequest
import fr.acinq.bitcoin.Chain
import fr.acinq.bitcoin.Satoshi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The guard for `docs/paid-requests.md`: a request nobody can pay any more -- because it was
 * paid, or because it expired -- is not drawn as a QR code, and what happened is said in its
 * place.
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

    /** Unreadable on purpose: an invoice whose expiry is unknown keeps its QR code. */
    private val invoice = "lnbcrt1250n1fakeinvoice"
    private val address = "bcrt1p" + "q".repeat(58)

    private fun bolt11(expirySeconds: Long, madeSecondsAgo: Long): String = Bolt11Invoice.create(
        chain = Chain.Regtest,
        amount = 1_250_000.msat,
        paymentHash = ByteVector32(Hex.decode("07".repeat(32))),
        privateKey = PrivateKey(ByteVector32(Hex.decode("05".repeat(32)))),
        description = Either.Left("tea"),
        minFinalCltvExpiryDelta = CltvExpiryDelta(144),
        features = Features(Feature.VariableLengthOnion to FeatureSupport.Mandatory, Feature.PaymentSecret to FeatureSupport.Mandatory),
        expirySeconds = expirySeconds,
        timestampSeconds = Clock.System.now().epochSeconds - madeSecondsAgo,
    ).write()

    private val expiredInvoice = bolt11(expirySeconds = 60, madeSecondsAgo = 3_600)
    private val liveInvoice = bolt11(expirySeconds = 3_600, madeSecondsAgo = 0)

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

    /**
     * D1 on the one surface that could never reach it: the QR code goes at **first sight** of the money,
     * before any confirmation and long before the group signs for it
     * (`docs/group-wavelength-unconfirmed-deposits.md`, Phase 5).
     */
    @Test
    fun `a group address paid in the mempool is not scannable, and says the money is still arriving`() = group(
        GroupWavelengthReceiveUIState.Loaded(
            address = address,
            addressAsked = true,
            addressPaid = PaidRequest(Satoshi(7_000), onTheOperatorsWord = false, onTheWay = PaidRequest.OnTheWay.MEMPOOL),
        )
    ) {
        assertEquals(0, onAllNodesWithContentDescription(bip21Uri(address)).fetchSemanticsNodes().size, "a paid address must not be paid again")
        onNodeWithText("Payment received").assertIsDisplayed()
        onNodeWithText("7,000 sats").assertIsDisplayed()
        onNodeWithText("Your device has seen this on the chain and is waiting for it to confirm. Ask for another address to keep your deposits apart.").assertIsDisplayed()
    }

    @Test
    fun `once it has confirmed the panel says what it is waiting for`() = group(
        GroupWavelengthReceiveUIState.Loaded(
            address = address,
            addressAsked = true,
            addressPaid = PaidRequest(Satoshi(7_000), onTheOperatorsWord = false, onTheWay = PaidRequest.OnTheWay.CONFIRMED),
        )
    ) {
        onNodeWithText("On the chain and confirming. It joins the wallet in a round once it is deep enough and enough of the group's devices are online. Ask for another address to keep your deposits apart.").assertIsDisplayed()
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

    // --- expiry: docs/paid-requests.md, Phase 7 -----------------------------------------

    @Test
    fun `an invoice inside its window is scannable`() = wavelength(
        WavelengthReceiveUIState(hasWallet = true, amount = "1250", invoice = liveInvoice)
    ) {
        assertEquals(1, onAllNodesWithContentDescription(liveInvoice).fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText("This invoice expired").fetchSemanticsNodes().size)
    }

    @Test
    fun `an expired invoice is not scannable, and says to ask for another`() = wavelength(
        WavelengthReceiveUIState(hasWallet = true, amount = "1250", invoice = expiredInvoice)
    ) {
        assertEquals(0, onAllNodesWithContentDescription(expiredInvoice).fetchSemanticsNodes().size, "nobody can pay it now")
        onNodeWithText("This invoice expired").assertIsDisplayed()
        onNodeWithText("Nobody can pay it now. Ask for another to be paid this amount.").assertIsDisplayed()
        onNodeWithText("Ask for another invoice").assertIsDisplayed()
        // Still readable, to check against what a payer says they tried to pay.
        onNodeWithText(expiredInvoice).assertIsDisplayed()
    }

    /** A payment that landed in the last second is still a payment. */
    @Test
    fun `paid outranks expired`() = wavelength(
        WavelengthReceiveUIState(hasWallet = true, amount = "1250", invoice = expiredInvoice, invoicePaid = Satoshi(1_250))
    ) {
        onNodeWithText("Payment received").assertIsDisplayed()
        onNodeWithText("1,250 sats").assertIsDisplayed()
        assertEquals(0, onAllNodesWithText("This invoice expired").fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithContentDescription(expiredInvoice).fetchSemanticsNodes().size)
    }

    @Test
    fun `an expired group invoice is not scannable either`() = group(
        GroupWavelengthReceiveUIState.Loaded(invoice = expiredInvoice, invoiceAmountSat = 1_250)
    ) {
        onNodeWithText("Lightning").performClick()
        assertEquals(0, onAllNodesWithContentDescription(expiredInvoice).fetchSemanticsNodes().size)
        onNodeWithText("This invoice expired").assertIsDisplayed()
    }
}
