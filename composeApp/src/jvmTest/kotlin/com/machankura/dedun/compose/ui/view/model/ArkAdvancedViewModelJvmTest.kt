package com.machankura.dedun.compose.ui.view.model

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.machankura.dedun.compose.wallet.ark.ArkBalance
import com.machankura.dedun.compose.wallet.ark.ArkExitFee
import com.machankura.dedun.compose.wallet.ark.ArkExitStatus
import com.machankura.dedun.compose.wallet.ark.ArkRecovery
import com.machankura.dedun.compose.wallet.ark.ArkUnavailability
import com.machankura.dedun.compose.wallet.ark.ArkVtxo
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

/** Leaving Ark, with and without the server -- `docs/bark-wallet.md`, Phase 9. */
@OptIn(ExperimentalCoroutinesApi::class)
class ArkAdvancedViewModelJvmTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val coin = ArkVtxo("aa:0", Satoshi(20_000), expiryHeight = 1_144, state = ArkVtxo.State.Spendable)

    private fun viewModel(wallet: FakeArkWallet) =
        ArkAdvancedViewModel(MutableStateFlow(ArkWalletState.Open(wallet, ArkRecovery.NotRun))).also { it.load() }

    @Test
    fun `with no wallet open there is nothing to leave`() {
        val vm = ArkAdvancedViewModel(MutableStateFlow(ArkWalletState.Unavailable(ArkUnavailability.TorOn)))
        vm.load()
        assertEquals(ArkAdvancedUIState.NoWallet, vm.uiState)
    }

    @Test
    fun `a wallet not exiting lists its coins and prices the exit, and opening it progresses exits`() {
        val wallet = FakeArkWallet().apply { vtxoList = listOf(coin) }
        val loaded = assertIs<ArkAdvancedUIState.Loaded>(viewModel(wallet).uiState)
        assertEquals(listOf(coin), loaded.vtxos)
        assertEquals(ArkExitFee(Satoshi(3_000), fundable = true), loaded.exitFee)
        assertFalse(loaded.exiting)
        assertTrue("progressExits" in wallet.calls)
    }

    @Test
    fun `moving everything onchain prices it for the address first, then moves it`() {
        val wallet = FakeArkWallet(balance = ArkBalance(Satoshi(20_000)))
        val vm = viewModel(wallet)
        vm.addressField.setTextAndPlaceCursorAtEnd("tb1qsomewhere")
        vm.estimateOffboard(onFailure = {})
        assertEquals(Satoshi(200), vm.offboardFee?.fee)

        var started = false
        vm.offboardAll(onStarted = { started = true }, onFailure = {})
        assertTrue(started)
        assertTrue("offboardAll" in wallet.calls)
    }

    @Test
    fun `an exit under way shows its progress, and is claimable only once the delay is over`() {
        val wallet = FakeArkWallet().apply {
            exitStatuses = listOf(ArkExitStatus(coin.id, ArkExitStatus.State.Claimable, error = null))
            claimableAt = 1_200
            tip = 1_100
        }
        var loaded = assertIs<ArkAdvancedUIState.Loaded>(viewModel(wallet).uiState)
        assertTrue(loaded.exiting)
        assertEquals(null, loaded.exitFee)
        assertFalse(loaded.claimable)

        wallet.tip = 1_200
        val vm = viewModel(wallet)
        loaded = assertIs<ArkAdvancedUIState.Loaded>(vm.uiState)
        assertTrue(loaded.claimable)

        var failed = false
        vm.claim(onStarted = {}, onFailure = { failed = true })
        assertTrue(failed, "a claim needs an address")
        vm.addressField.setTextAndPlaceCursorAtEnd("tb1qmine")
        vm.claim(onStarted = {}, onFailure = {})
        assertTrue("claimExits" in wallet.calls)
    }

    @Test
    fun `starting the exit asks Bark to exit everything`() {
        val wallet = FakeArkWallet().apply { vtxoList = listOf(coin) }
        val vm = viewModel(wallet)
        vm.startEmergencyExit(onStarted = {}, onFailure = {})
        assertTrue("startEmergencyExit" in wallet.calls)
    }
}
