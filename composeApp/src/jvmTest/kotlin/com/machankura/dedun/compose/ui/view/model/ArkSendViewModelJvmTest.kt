package com.machankura.dedun.compose.ui.view.model

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.machankura.dedun.compose.wallet.ark.ArkBalance
import com.machankura.dedun.compose.wallet.ark.ArkError
import com.machankura.dedun.compose.wallet.ark.ArkNetwork
import com.machankura.dedun.compose.wallet.ark.ArkPaymentMethod
import com.machankura.dedun.compose.wallet.ark.ArkPaymentOption
import com.machankura.dedun.compose.wallet.ark.ArkPaymentRequest
import com.machankura.dedun.compose.wallet.ark.ArkRecovery
import com.machankura.dedun.compose.wallet.ark.ArkSendResult
import com.machankura.dedun.compose.wallet.ark.ArkUnavailability
import com.machankura.dedun.compose.wallet.ark.ArkWalletState
import com.machankura.dedun.compose.wallet.ark.FakeArkWallet
import fr.acinq.bitcoin.Satoshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Paying from the profile's Ark wallet -- `docs/bark-wallet.md`, Phase 6. */
@OptIn(ExperimentalCoroutinesApi::class)
class ArkSendViewModelJvmTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val invoice = "lntbs50u1pinvoice"
    private val address = "tark1someone"
    private val lnAddress = "alice@example.com"

    private fun wallet(network: ArkNetwork = ArkNetwork.Signet, spendable: Long = 100_000) = FakeArkWallet(network = network, balance = ArkBalance(Satoshi(spendable))).apply {
        requests[invoice] = ArkPaymentRequest(Satoshi(5_000), "coffee", listOf(ArkPaymentOption(ArkPaymentMethod.Bolt11(invoice), requiresAmount = false)), preferred = null)
        requests[address] = ArkPaymentRequest(null, null, listOf(ArkPaymentOption(ArkPaymentMethod.ArkAddress(address), requiresAmount = true)), preferred = null)
        requests[lnAddress] = ArkPaymentRequest(null, null, listOf(ArkPaymentOption(ArkPaymentMethod.LightningAddress(lnAddress), requiresAmount = true)), preferred = null)
    }

    private fun viewModel(wallet: FakeArkWallet) = ArkSendViewModel(MutableStateFlow(ArkWalletState.Open(wallet, ArkRecovery.NotRun)))

    @Test
    fun `with no wallet open there is nothing to send from`() {
        val vm = ArkSendViewModel(MutableStateFlow(ArkWalletState.Unavailable(ArkUnavailability.TorOn)))
        assertEquals(ArkSendUIState.NoWallet, vm.uiState)
    }

    @Test
    fun `text nothing can pay stays on the field and says so`() {
        val vm = viewModel(wallet())
        vm.requestField.setTextAndPlaceCursorAtEnd("hello")
        vm.parse()
        assertEquals(ArkSendUIState.Entering(ArkSendProblem.NothingToPay), vm.uiState)
    }

    @Test
    fun `an invoice that names its amount is estimated at once and paid without a second amount`() {
        val wallet = wallet()
        val vm = viewModel(wallet)
        vm.requestField.setTextAndPlaceCursorAtEnd(invoice)
        vm.parse()
        val review = assertIs<ArkSendUIState.Review>(vm.uiState)
        assertEquals(Satoshi(5_000), review.amount)
        assertEquals(Satoshi(5_010), review.fee?.gross)
        assertTrue(review.canSend)

        vm.send()
        assertIs<ArkSendUIState.Sent>(vm.uiState)
        assertEquals(Triple<ArkPaymentMethod, Satoshi?, String?>(ArkPaymentMethod.Bolt11(invoice), null, null), wallet.sent.single())
    }

    @Test
    fun `an address asks for an amount, and is paid that amount`() {
        val wallet = wallet()
        val vm = viewModel(wallet)
        vm.requestField.setTextAndPlaceCursorAtEnd(address)
        vm.parse()
        assertEquals(ArkSendProblem.AmountNeeded, assertIs<ArkSendUIState.Review>(vm.uiState).problem)

        vm.amountField.setTextAndPlaceCursorAtEnd("2,500")
        vm.reestimate()
        assertTrue(assertIs<ArkSendUIState.Review>(vm.uiState).canSend)
        vm.send()
        assertEquals(Satoshi(2_500), wallet.sent.single().second)
    }

    @Test
    fun `a Lightning address takes a note for the payee, and an invoice does not`() {
        assertTrue(ArkSendViewModel.takesComment(ArkPaymentMethod.LightningAddress(lnAddress)))
        assertTrue(ArkSendViewModel.takesComment(ArkPaymentMethod.Lnurl("lnurl1")))
        assertFalse(ArkSendViewModel.takesComment(ArkPaymentMethod.Bolt11(invoice)))

        val wallet = wallet()
        val vm = viewModel(wallet)
        vm.requestField.setTextAndPlaceCursorAtEnd(lnAddress)
        vm.parse()
        vm.amountField.setTextAndPlaceCursorAtEnd("1000")
        vm.commentField.setTextAndPlaceCursorAtEnd("thanks")
        vm.reestimate()
        vm.send()
        assertEquals("thanks", wallet.sent.single().third)
    }

    @Test
    fun `on mainnet a send over the cap is refused before it is estimated`() {
        val wallet = wallet(ArkNetwork.Mainnet, spendable = 500_000)
        val vm = viewModel(wallet)
        vm.requestField.setTextAndPlaceCursorAtEnd(address)
        vm.parse()
        vm.amountField.setTextAndPlaceCursorAtEnd("100001")
        vm.reestimate()
        assertEquals(ArkSendProblem.AboveCap, assertIs<ArkSendUIState.Review>(vm.uiState).problem)
        assertFalse("estimateFee" in wallet.calls)
    }

    @Test
    fun `a payment and its fee over the spendable balance cannot be sent`() {
        val vm = viewModel(wallet(spendable = 5_005))
        vm.requestField.setTextAndPlaceCursorAtEnd(invoice)
        vm.parse()
        val review = assertIs<ArkSendUIState.Review>(vm.uiState)
        assertEquals(ArkSendProblem.NotEnough, review.problem)
        assertFalse(review.canSend)
    }

    @Test
    fun `a send that fails says so, and change goes back to the field`() {
        val wallet = wallet()
        wallet.failNext = "send" to ArkError.Other("route not found")
        val vm = viewModel(wallet)
        vm.requestField.setTextAndPlaceCursorAtEnd(invoice)
        vm.parse()
        vm.send()
        assertIs<ArkSendUIState.Failed>(vm.uiState)
        vm.change()
        assertEquals(ArkSendUIState.Entering(), vm.uiState)
        assertEquals(invoice, vm.requestField.text.toString())
    }
}
