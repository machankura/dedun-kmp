package com.machankura.dedun.compose.ui.view.model

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import com.machankura.dedun.compose.wallet.ark.ArkBalance
import com.machankura.dedun.compose.wallet.ark.ArkDirection
import com.machankura.dedun.compose.wallet.ark.ArkEvent
import com.machankura.dedun.compose.wallet.ark.ArkMovement
import com.machankura.dedun.compose.wallet.ark.ArkMovementStatus
import com.machankura.dedun.compose.wallet.ark.ArkNetwork
import com.machankura.dedun.compose.wallet.ark.ArkRail
import com.machankura.dedun.compose.wallet.ark.ArkRecovery
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Asking to be paid into the profile's Ark wallet -- `docs/bark-wallet.md`, Phase 5. */
@OptIn(ExperimentalCoroutinesApi::class)
class ArkReceiveViewModelJvmTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun incoming(hash: String?, status: ArkMovementStatus = ArkMovementStatus.Succeeded) =
        ArkMovement(9, status, ArkDirection.Incoming, if (hash != null) ArkRail.Lightning else ArkRail.Ark, Satoshi(10_000), Satoshi(0), null, hash, null, null)

    @Test
    fun `with no wallet open there is nothing to ask with`() {
        val vm = ArkReceiveViewModel(MutableStateFlow(ArkWalletState.Unavailable(ArkUnavailability.TorOn)))
        assertEquals(ArkReceiveUIState.NoWallet, vm.uiState)
    }

    @Test
    fun `a request with no amount is made at once, and has no invoice`() {
        val wallet = FakeArkWallet()
        val vm = ArkReceiveViewModel(MutableStateFlow(ArkWalletState.Open(wallet, ArkRecovery.NotRun)))
        val ready = assertIs<ArkReceiveUIState.Ready>(vm.uiState)
        assertNotNull(ready.request.arkAddress)
        assertNull(ready.request.bolt11)
        assertNull(ready.lightningFee)
    }

    @Test
    fun `an amount adds an invoice and its fee`() {
        val wallet = FakeArkWallet()
        val vm = ArkReceiveViewModel(MutableStateFlow(ArkWalletState.Open(wallet, ArkRecovery.NotRun)))
        vm.amountField.setTextAndPlaceCursorAtEnd("10,000")
        vm.request()
        val ready = assertIs<ArkReceiveUIState.Ready>(vm.uiState)
        assertEquals("lntbs10000fake", ready.request.bolt11)
        assertEquals(Satoshi(10_000), ready.lightningFee?.gross)
        assertTrue("lightningReceiveFee" in wallet.calls)
    }

    @Test
    fun `on mainnet an amount that would take the wallet past the cap makes no invoice`() {
        val wallet = FakeArkWallet(network = ArkNetwork.Mainnet, balance = ArkBalance(Satoshi(95_000)))
        val vm = ArkReceiveViewModel(MutableStateFlow(ArkWalletState.Open(wallet, ArkRecovery.NotRun)))
        vm.amountField.setTextAndPlaceCursorAtEnd("6000")
        vm.request()
        assertTrue(vm.aboveCap)
        assertNull(assertIs<ArkReceiveUIState.Ready>(vm.uiState).request.bolt11)

        vm.amountField.setTextAndPlaceCursorAtEnd("5000")
        vm.request()
        assertTrue(!vm.aboveCap)
        assertNotNull(assertIs<ArkReceiveUIState.Ready>(vm.uiState).request.bolt11)
    }

    @Test
    fun `the payment that answers the invoice settles it, and another one does not`() {
        val wallet = FakeArkWallet()
        val vm = ArkReceiveViewModel(MutableStateFlow(ArkWalletState.Open(wallet, ArkRecovery.NotRun)))
        vm.amountField.setTextAndPlaceCursorAtEnd("10000")
        vm.request()

        wallet.events.tryEmit(ArkEvent.MovementChanged(incoming("ff".repeat(32))))
        assertNull(assertIs<ArkReceiveUIState.Ready>(vm.uiState).received)
        wallet.events.tryEmit(ArkEvent.MovementChanged(incoming("00".repeat(32), ArkMovementStatus.Pending)))
        assertNull(assertIs<ArkReceiveUIState.Ready>(vm.uiState).received)
        wallet.events.tryEmit(ArkEvent.MovementChanged(incoming("00".repeat(32))))
        assertEquals(Satoshi(10_000), assertIs<ArkReceiveUIState.Ready>(vm.uiState).received?.amount)
    }

    @Test
    fun `an Ark payment settles a request with no invoice`() {
        val wallet = FakeArkWallet()
        val vm = ArkReceiveViewModel(MutableStateFlow(ArkWalletState.Open(wallet, ArkRecovery.NotRun)))
        wallet.events.tryEmit(ArkEvent.MovementChanged(incoming(hash = null)))
        assertNotNull(assertIs<ArkReceiveUIState.Ready>(vm.uiState).received)
    }
}
