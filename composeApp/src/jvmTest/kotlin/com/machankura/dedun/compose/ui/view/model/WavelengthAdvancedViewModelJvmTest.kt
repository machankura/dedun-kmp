package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.wallet.wavelength.WalletState
import engineering.lightning.wavelength.DescriptorWalletRequest
import engineering.lightning.wavelength.Network
import engineering.lightning.wavelength.RuntimeConfig
import engineering.lightning.wavelength.engine.WalletEngine
import engineering.lightning.wavelength.model.ExitPlanEntry
import engineering.lightning.wavelength.model.GetExitPlanResult
import engineering.lightning.wavelength.testing.FakeFacadeBindings
import engineering.lightning.wavelength.testing.FakeWavelengthClient
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

@OptIn(ExperimentalCoroutinesApi::class)
class WavelengthAdvancedViewModelJvmTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun TestScope.model(bindings: FakeFacadeBindings = FakeFacadeBindings(initialConfirmedSat = 40_000)): WavelengthAdvancedViewModel {
        val client = FakeWavelengthClient(bindings)
        val config = RuntimeConfig.preset(Network.SIGNET, dataDir = "/tmp/x")
        client.startDescriptorWallet(config, DescriptorWalletRequest(listOf("tr(x)")))
        val engine = WalletEngine(client, backgroundScope, config)
        engine.start()
        return WavelengthAdvancedViewModel(MutableStateFlow(WalletState.Open(engine, Network.SIGNET, recovery = null))).also { it.load() }
    }

    private fun run(block: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher(), testBody = block)

    private fun WavelengthAdvancedViewModel.loaded() = assertIs<WavelengthAdvancedUIState.Loaded>(uiState)

    @Test
    fun `moving onchain asks for an address, prices it, and moves only after a yes`() = run {
        val model = model()
        model.priceMoveAll()
        assertEquals(WavelengthAdvancedProblem.NeedsAddress, model.loaded().problem)

        model.onAddressChange(" tb1pmine ")
        assertEquals("tb1pmine", model.loaded().address)
        model.priceMoveAll()
        assertIs<WavelengthExitStep.MoveAll>(model.loaded().step)

        model.confirm()
        assertNull(model.loaded().step)
        assertEquals(WavelengthAdvancedNotice.MoveStarted, model.loaded().notice)
    }

    @Test
    fun `saying no moves nothing`() = run {
        val model = model()
        model.onAddressChange("tb1pmine")
        model.priceMoveAll()
        model.dismissStep()
        assertNull(model.loaded().step)
        assertNull(model.loaded().notice)
    }

    @Test
    fun `an exit that needs funding says how much and where, and offers nothing else`() = run {
        val bindings = FakeFacadeBindings()
        bindings.exitPlanHandler = {
            GetExitPlanResult(
                plans = listOf(ExitPlanEntry(outpoint = "o", fundingAddress = "tb1pfund")),
                canStart = false,
                totalFundingShortfallSat = 5_000,
            )
        }
        val model = model(bindings)
        model.planEmergency()
        val step = assertIs<WavelengthExitStep.Emergency>(model.loaded().step)
        assertEquals("tb1pfund", step.fundingAddress)
        assertEquals(false, step.plan.canStart)
    }

    @Test
    fun `an exit starts only after a yes, with nothing left to refuse`() = run {
        val bindings = FakeFacadeBindings()
        bindings.exitPlanHandler = { GetExitPlanResult(canStart = true) }
        val model = model(bindings)
        model.planEmergency()
        assertIs<WavelengthExitStep.Emergency>(model.loaded().step)
        model.confirm()
        assertEquals(WavelengthAdvancedNotice.ExitStarted, model.loaded().notice)
    }

    @Test
    fun `a failure says which, and leaves the address`() = run {
        val bindings = FakeFacadeBindings()
        val model = model(bindings)
        model.onAddressChange("tb1pmine")
        bindings.failNext("prepareSend", "something broke")
        model.priceMoveAll()
        assertEquals(WavelengthAdvancedProblem.CouldNotMove, model.loaded().problem)
        assertEquals("tb1pmine", model.loaded().address)
    }

    @Test
    fun `with no wallet open there is nothing to do`() {
        val model = WavelengthAdvancedViewModel(MutableStateFlow(WalletState.Opening))
        assertEquals(WavelengthAdvancedUIState.NoWallet, model.uiState)
        model.load()
        model.priceMoveAll()
        assertEquals(WavelengthAdvancedUIState.NoWallet, model.uiState)
    }
}
