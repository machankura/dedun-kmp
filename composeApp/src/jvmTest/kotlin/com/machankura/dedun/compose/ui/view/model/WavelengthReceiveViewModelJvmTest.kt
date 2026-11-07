package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.wallet.wavelength.WalletState
import engineering.lightning.wavelength.DescriptorWalletRequest
import engineering.lightning.wavelength.Network
import engineering.lightning.wavelength.RuntimeConfig
import engineering.lightning.wavelength.engine.WalletEngine
import engineering.lightning.wavelength.model.EntryKind
import engineering.lightning.wavelength.testing.FakeFacadeBindings
import engineering.lightning.wavelength.testing.FakeWavelengthClient
import fr.acinq.bitcoin.Satoshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
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
import kotlin.test.assertNotEquals
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

    /**
     * A paid invoice stops being a QR code, and what the daemon says arrived is what is said --
     * which need not be what was asked for (`docs/paid-requests.md`, Phase 2).
     */
    @Test
    fun `a paid invoice is marked paid, with what arrived`() = run {
        val bindings = FakeFacadeBindings()
        val open = open(backgroundScope, bindings)
        val model = WavelengthReceiveViewModel(MutableStateFlow<WalletState>(open))
        model.onAmountChange("5000")
        model.makeInvoice()
        assertNotNull(model.uiState.invoice)
        assertNull(model.uiState.invoicePaid, "nobody has paid it yet")

        bindings.settle(entry(open, EntryKind.RECEIVE))
        assertEquals(Satoshi(5_000), settled(model) { it.invoicePaid })
        assertNotNull(model.uiState.invoice, "kept to read against what the payer says they paid")
    }

    @Test
    fun `a failed receive is not a payment, and a new amount clears the last one`() = run {
        val bindings = FakeFacadeBindings()
        val open = open(backgroundScope, bindings)
        val model = WavelengthReceiveViewModel(MutableStateFlow<WalletState>(open))
        model.onAmountChange("5000")
        model.makeInvoice()
        bindings.fail(entry(open, EntryKind.RECEIVE))
        open.engine.refresh()
        assertNull(model.uiState.invoicePaid, "a failed receive is no money on its way")

        model.onAmountChange("5000")
        model.makeInvoice()
        bindings.settle(entry(open, EntryKind.RECEIVE, which = 1))
        assertEquals(Satoshi(5_000), settled(model) { it.invoicePaid })
        model.onAmountChange("6000")
        assertNull(model.uiState.invoicePaid, "the invoice went, so its payment goes with it")
    }

    /** A boarding address is reusable, so this is privacy rather than loss -- but it still comes off the screen. */
    @Test
    fun `a paid boarding address is marked paid, and another can be asked for`() = run {
        val bindings = FakeFacadeBindings()
        val open = open(backgroundScope, bindings)
        val model = WavelengthReceiveViewModel(MutableStateFlow<WalletState>(open))
        model.makeAddress()
        val first = model.uiState.address
        assertNotNull(first)
        assertNull(model.uiState.addressPaid)

        // The deposit's amount is only known once it arrives: the hint was zero.
        bindings.settle(entry(open, EntryKind.DEPOSIT))
        assertEquals(Satoshi(0), settled(model) { it.addressPaid })

        model.makeAddress()
        assertNull(model.uiState.addressPaid, "a fresh address has not been paid")
        assertNotEquals(first, model.uiState.address)
    }

    /** Nothing is claimed for the other rail: one request's payment is not the other's. */
    @Test
    fun `a paid invoice says nothing about the address`() = run {
        val bindings = FakeFacadeBindings()
        val open = open(backgroundScope, bindings)
        val model = WavelengthReceiveViewModel(MutableStateFlow<WalletState>(open))
        model.makeAddress()
        model.onAmountChange("5000")
        model.makeInvoice()
        bindings.settle(entry(open, EntryKind.RECEIVE))
        assertEquals(Satoshi(5_000), settled(model) { it.invoicePaid })
        assertNull(model.uiState.addressPaid)
    }

    /** The id of the wallet's [which]-th activity entry of [kind], which is what a payment is matched on. */
    private suspend fun entry(open: WalletState.Open, kind: EntryKind, which: Int = 0): String {
        withTimeout(5_000) {
            while (open.engine.snapshot.value.activity.count { it.kind == kind } <= which) {
                open.engine.refresh()
                delay(10)
            }
        }
        return open.engine.snapshot.value.activity.filter { it.kind == kind }.sortedBy { it.id }[which].id
    }

    private suspend fun <T> settled(model: WavelengthReceiveViewModel, read: (WavelengthReceiveUIState) -> T?): T? =
        withTimeout(5_000) {
            while (read(model.uiState) == null) delay(10)
            read(model.uiState)
        }
}
