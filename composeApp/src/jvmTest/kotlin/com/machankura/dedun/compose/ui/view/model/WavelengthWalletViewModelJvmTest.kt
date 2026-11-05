package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.wallet.wavelength.WalletState
import com.machankura.dedun.compose.wallet.wavelength.WalletUnavailability
import engineering.lightning.wavelength.Network
import engineering.lightning.wavelength.engine.WalletEngine
import engineering.lightning.wavelength.model.Balance
import engineering.lightning.wavelength.testing.FakeFacadeBindings
import engineering.lightning.wavelength.testing.FakeWavelengthClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import engineering.lightning.wavelength.DescriptorWalletRequest
import engineering.lightning.wavelength.RuntimeConfig
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class WavelengthWalletViewModelJvmTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(state: MutableStateFlow<WalletState>) = WavelengthWalletViewModel(state, onConsent = {}, onRetry = {})

    @Test
    fun `each holder state has a UI state`() {
        val state = MutableStateFlow<WalletState>(WalletState.None)
        val model = viewModel(state)
        assertEquals(WavelengthWalletUIState.Loading, model.uiState)
        state.value = WalletState.Unavailable(WalletUnavailability.TorOn)
        assertEquals(WavelengthWalletUIState.Unavailable(WalletUnavailability.TorOn), model.uiState)
        state.value = WalletState.Opening
        assertEquals(WavelengthWalletUIState.Opening, model.uiState)
        state.value = WalletState.Recovering
        assertEquals(WavelengthWalletUIState.Restoring, model.uiState)
        state.value = WalletState.WrongIdentity
        assertEquals(WavelengthWalletUIState.WrongIdentity, model.uiState)
    }

    @Test
    fun `an open wallet shows what its engine knows`() = runTest(UnconfinedTestDispatcher()) {
        val bindings = FakeFacadeBindings(initialConfirmedSat = 5_000)
        val client = FakeWavelengthClient(bindings)
        val config = RuntimeConfig.preset(Network.SIGNET, dataDir = "/tmp/x")
        client.startDescriptorWallet(config, DescriptorWalletRequest(listOf("tr(x)")))
        val engine = WalletEngine(client, backgroundScope, config)
        engine.start()

        val state = MutableStateFlow<WalletState>(WalletState.Opening)
        val model = viewModel(state)
        state.value = WalletState.Open(engine, Network.SIGNET, recovery = null)

        val loaded = assertIs<WavelengthWalletUIState.Loaded>(model.uiState)
        assertEquals(Network.SIGNET, loaded.network)
        assertEquals(Balance(confirmedSat = 5_000), loaded.balance)
        assertEquals(false, loaded.syncing)
    }
}
