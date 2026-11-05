package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.wallet.wavelength.WalletState
import engineering.lightning.wavelength.DescriptorWalletRequest
import engineering.lightning.wavelength.Network
import engineering.lightning.wavelength.RuntimeConfig
import engineering.lightning.wavelength.engine.WalletEngine
import engineering.lightning.wavelength.testing.FakeFacadeBindings
import engineering.lightning.wavelength.testing.FakeWavelengthClient
import kotlinx.coroutines.CoroutineScope
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WavelengthReceiveViewModelJvmTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun open(scope: CoroutineScope, bindings: FakeFacadeBindings, network: Network = Network.SIGNET): WalletState.Open {
        val client = FakeWavelengthClient(bindings)
        val config = RuntimeConfig.preset(Network.SIGNET, dataDir = "/tmp/x")
        client.startDescriptorWallet(config, DescriptorWalletRequest(listOf("tr(x)")))
        val engine = WalletEngine(client, scope, config)
        engine.start()
        return WalletState.Open(engine, network, recovery = null)
    }

    private suspend fun TestScope.model(bindings: FakeFacadeBindings = FakeFacadeBindings(), network: Network = Network.SIGNET) =
        WavelengthReceiveViewModel(MutableStateFlow<WalletState>(open(backgroundScope, bindings, network)))

    private fun run(block: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher(), testBody = block)

    @Test
    fun `an amount makes an invoice`() = run {
        val model = model()
        assertTrue(model.uiState.hasWallet)
        model.onAmountChange("5,000 sats")
        assertEquals("5000", model.uiState.amount)
        model.makeInvoice()
        assertNotNull(model.uiState.invoice)
        assertFalse(model.uiState.working)
    }

    @Test
    fun `no amount asks for one`() = run {
        val model = model()
        model.makeInvoice()
        assertEquals(WavelengthReceiveProblem.NeedsAmount, model.uiState.problem)
        assertNull(model.uiState.invoice)
    }

    @Test
    fun `the cap is checked on mainnet against what is held, before the daemon is asked`() = run {
        val bindings = FakeFacadeBindings(initialConfirmedSat = 90_000)
        val model = model(bindings, Network.MAINNET)
        model.onAmountChange("20000")
        model.makeInvoice()
        assertEquals(WavelengthReceiveProblem.AboveCap, model.uiState.problem)
        model.onAmountChange("10000")
        model.makeInvoice()
        assertNull(model.uiState.problem)
        assertNotNull(model.uiState.invoice)
    }

    @Test
    fun `each refusal says which`() = run {
        val bindings = FakeFacadeBindings()
        val model = model(bindings)
        model.onAmountChange("300000")
        bindings.failNext("receive", "amount exceeds the operator's maximum VTXO size: receive of 0.00300000 BTC exceeds the per-VTXO maximum of 0.00050000 BTC")
        model.makeInvoice()
        assertEquals(WavelengthReceiveProblem.AboveCoinLimit, model.uiState.problem)
        bindings.failNext("receive", "something else")
        model.makeInvoice()
        assertEquals(WavelengthReceiveProblem.CouldNotMake, model.uiState.problem)
    }

    @Test
    fun `a timed out request looks for the invoice it may have made, and never makes a second blindly`() = run {
        val bindings = FakeFacadeBindings()
        val model = model(bindings)
        model.onAmountChange("7000")
        bindings.failNext("receive", "receive outcome uncertain; reconcile Activity before retrying")
        model.makeInvoice()
        assertEquals(WavelengthReceiveProblem.Uncertain, model.uiState.problem)
        assertNull(model.uiState.invoice)
    }

    @Test
    fun `a new amount clears the last invoice`() = run {
        val model = model()
        model.onAmountChange("5000")
        model.makeInvoice()
        model.onAmountChange("6000")
        assertNull(model.uiState.invoice)
    }

    @Test
    fun `an address is made when asked`() = run {
        val model = model()
        assertNull(model.uiState.address)
        model.makeAddress()
        assertNotNull(model.uiState.address)
    }

    @Test
    fun `with no wallet open there is nothing to ask`() {
        val model = WavelengthReceiveViewModel(MutableStateFlow(WalletState.Opening))
        assertFalse(model.uiState.hasWallet)
        model.makeAddress()
        assertNull(model.uiState.address)
    }
}
