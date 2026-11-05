package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.wallet.wavelength.WalletState
import engineering.lightning.wavelength.DescriptorWalletRequest
import engineering.lightning.wavelength.Network
import engineering.lightning.wavelength.RuntimeConfig
import engineering.lightning.wavelength.engine.WalletEngine
import engineering.lightning.wavelength.testing.FakeFacadeBindings
import engineering.lightning.wavelength.testing.FakeWavelengthClient
import fr.acinq.bitcoin.Satoshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WavelengthSendViewModelJvmTest {
    private val invoiceWithAmount = "lntbs50u1pabc"
    private val amountless = "lntbs1pabc"
    private val onchain = "tb1pzzzz"

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun TestScope.model(
        bindings: FakeFacadeBindings = FakeFacadeBindings(initialConfirmedSat = 200_000),
        network: Network = Network.SIGNET,
        invoiceFor: (Satoshi) -> String? = { "lntbs${it.sat}n1pfromaddress" },
    ): WavelengthSendViewModel {
        val client = FakeWavelengthClient(bindings)
        val config = RuntimeConfig.preset(Network.SIGNET, dataDir = "/tmp/x")
        client.startDescriptorWallet(config, DescriptorWalletRequest(listOf("tr(x)")))
        val engine = WalletEngine(client, backgroundScope, config)
        engine.start()
        return WavelengthSendViewModel(MutableStateFlow(WalletState.Open(engine, network, recovery = null))) { _, amount, _ -> invoiceFor(amount) }
    }

    private fun run(block: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher(), testBody = block)

    private fun WavelengthSendViewModel.problem() = assertIs<WavelengthSendUIState.Entering>(uiState).problem

    @Test
    fun `an invoice with an amount asks for none, and goes to review then sent`() = run {
        val model = model()
        model.onDestinationChange(invoiceWithAmount)
        assertEquals(false, assertIs<WavelengthSendUIState.Entering>(model.uiState).needsAmount)
        model.review()
        assertIs<WavelengthSendUIState.Reviewing>(model.uiState)
        model.send()
        assertEquals(WavelengthSendUIState.Sent, model.uiState)
    }

    @Test
    fun `an amountless invoice, an onchain address and a Lightning address each need an amount`() = run {
        val model = model()
        for (destination in listOf(amountless, onchain, "alice@example.com")) {
            model.onDestinationChange(destination)
            assertTrue(assertIs<WavelengthSendUIState.Entering>(model.uiState).needsAmount, destination)
            model.review()
            assertEquals(WavelengthSendProblem.NeedsAmount, model.problem(), destination)
        }
    }

    @Test
    fun `an offer and an LNURL say they are not supported, not that they are mistyped`() = run {
        val model = model()
        model.onDestinationChange("lno1pqqqq")
        model.review()
        assertEquals(WavelengthSendProblem.Unsupported, model.problem())
    }

    @Test
    fun `a Lightning address is paid through its invoice, and one that gives none says so`() = run {
        val working = model()
        working.onDestinationChange("alice@example.com")
        working.onAmountChange("1000")
        working.review()
        assertIs<WavelengthSendUIState.Reviewing>(working.uiState)

        val failing = model(invoiceFor = { null })
        failing.onDestinationChange("alice@example.com")
        failing.onAmountChange("1000")
        failing.review()
        assertEquals(WavelengthSendProblem.AddressFailed, failing.problem())
    }

    @Test
    fun `an invoice with a fraction of a sat is refused in words`() = run {
        val model = model()
        model.onDestinationChange("lntbs1231p1abc")
        model.review()
        assertEquals(WavelengthSendProblem.Unrepresentable, model.problem())
    }

    @Test
    fun `the cap is mainnet's alone`() = run {
        val mainnet = model(network = Network.MAINNET)
        mainnet.onDestinationChange(onchain)
        mainnet.onAmountChange("100001")
        mainnet.review()
        assertEquals(WavelengthSendProblem.AboveCap, mainnet.problem())

        val signet = model()
        signet.onDestinationChange(onchain)
        signet.onAmountChange("100001")
        signet.review()
        assertIs<WavelengthSendUIState.Reviewing>(signet.uiState)
    }

    @Test
    fun `a stale quote goes back to the form and quotes again, keeping what was typed`() = run {
        val bindings = FakeFacadeBindings(initialConfirmedSat = 200_000)
        val model = model(bindings)
        model.onDestinationChange(invoiceWithAmount)
        model.review()
        bindings.failNext("sendPrepared", "send intent is missing, expired, or already consumed")
        model.send()
        val entering = assertIs<WavelengthSendUIState.Entering>(model.uiState)
        assertEquals(invoiceWithAmount, entering.destination)
        assertEquals(WavelengthSendProblem.QuoteExpired, entering.problem)
    }

    @Test
    fun `change from the review keeps what was typed`() = run {
        val model = model()
        model.onDestinationChange(onchain)
        model.onAmountChange("5000")
        model.review()
        model.change()
        val entering = assertIs<WavelengthSendUIState.Entering>(model.uiState)
        assertEquals(onchain, entering.destination)
        assertEquals("5000", entering.amount)
        assertNull(entering.problem)
    }

    @Test
    fun `with no wallet open there is no form`() {
        assertEquals(WavelengthSendUIState.NoWallet, WavelengthSendViewModel(MutableStateFlow(WalletState.Opening)) { _, _, _ -> null }.uiState)
    }
}
