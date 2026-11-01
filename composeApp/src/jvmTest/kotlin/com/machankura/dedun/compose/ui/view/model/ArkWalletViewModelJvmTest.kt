package com.machankura.dedun.compose.ui.view.model

import com.machankura.dedun.compose.wallet.ark.ArkBalance
import com.machankura.dedun.compose.wallet.ark.ArkDirection
import com.machankura.dedun.compose.wallet.ark.ArkError
import com.machankura.dedun.compose.wallet.ark.ArkEvent
import com.machankura.dedun.compose.wallet.ark.ArkExpiry
import com.machankura.dedun.compose.wallet.ark.ArkMovement
import com.machankura.dedun.compose.wallet.ark.ArkMovementStatus
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
import kotlin.test.assertTrue

/** The wallet read for a screen, and kept current by its events -- `docs/bark-wallet.md`, Phase 4. */
@OptIn(ExperimentalCoroutinesApi::class)
class ArkWalletViewModelJvmTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val state = MutableStateFlow<ArkWalletState>(ArkWalletState.None)
    private val actions = mutableListOf<String>()

    private fun viewModel() = ArkWalletViewModel(state, onConsent = { actions += "consent" }, onRetry = { actions += "retry" })

    private fun movement(id: Long, status: ArkMovementStatus = ArkMovementStatus.Succeeded) = ArkMovement(
        id = id,
        status = status,
        direction = ArkDirection.Incoming,
        rail = ArkRail.Ark,
        amount = Satoshi(1_000 * id),
        fee = Satoshi(0),
        counterparty = null,
        paymentHash = null,
        createdAt = null,
        completedAt = null,
    )

    @Test
    fun `each state of the holder is a state of the screen`() {
        val vm = viewModel()
        assertEquals(ArkWalletUIState.Loading, vm.uiState)
        state.value = ArkWalletState.Unavailable(ArkUnavailability.TorOn)
        assertEquals(ArkWalletUIState.Unavailable(ArkUnavailability.TorOn), vm.uiState)
        state.value = ArkWalletState.Recovering
        assertEquals(ArkWalletUIState.Restoring, vm.uiState)
        state.value = ArkWalletState.Opening
        assertEquals(ArkWalletUIState.Opening, vm.uiState)
        state.value = ArkWalletState.Failed(ArkError.ServerUnreachable("no"))
        assertEquals(false, assertIs<ArkWalletUIState.Failed>(vm.uiState).opened)
    }

    @Test
    fun `an open wallet is read for its balance, history and deadline`() {
        val wallet = FakeArkWallet(balance = ArkBalance(Satoshi(21_000), pendingInRound = Satoshi(500)), movements = listOf(movement(2), movement(1)), tip = 1_000, firstExpiry = 1_010)
        val vm = viewModel()
        state.value = ArkWalletState.Open(wallet, ArkRecovery.Completed(complete = false, recovered = Satoshi(0), failedIds = listOf("x")))
        val loaded = assertIs<ArkWalletUIState.Loaded>(vm.uiState)
        assertEquals(Satoshi(21_000), loaded.balance.spendable)
        assertEquals(listOf(2L, 1L), loaded.movements.map { it.id })
        // Ten blocks left on signet is inside its twelve-block threshold.
        assertEquals(ArkExpiry.RenewSoon(10), loaded.expiry)
        assertTrue(loaded.recoveryIncomplete)
    }

    @Test
    fun `an update replaces its row, a new movement goes on top, and the balance is read again`() {
        val wallet = FakeArkWallet(movements = listOf(movement(1, ArkMovementStatus.Pending)))
        val vm = viewModel()
        state.value = ArkWalletState.Open(wallet, ArkRecovery.NotRun)

        wallet.balance = ArkBalance(Satoshi(1_000))
        wallet.events.tryEmit(ArkEvent.MovementChanged(movement(1, ArkMovementStatus.Succeeded)))
        var loaded = assertIs<ArkWalletUIState.Loaded>(vm.uiState)
        assertEquals(listOf(ArkMovementStatus.Succeeded), loaded.movements.map { it.status })
        assertEquals(Satoshi(1_000), loaded.balance.spendable)

        wallet.events.tryEmit(ArkEvent.MovementChanged(movement(2)))
        loaded = assertIs<ArkWalletUIState.Loaded>(vm.uiState)
        assertEquals(listOf(2L, 1L), loaded.movements.map { it.id })
    }

    @Test
    fun `dropped events mean reading everything again`() {
        val wallet = FakeArkWallet()
        val vm = viewModel()
        state.value = ArkWalletState.Open(wallet, ArkRecovery.NotRun)
        wallet.movements = listOf(movement(5))
        wallet.events.tryEmit(ArkEvent.Lagging)
        assertEquals(listOf(5L), assertIs<ArkWalletUIState.Loaded>(vm.uiState).movements.map { it.id })
    }

    @Test
    fun `renewing asks the fee first, and nothing due is said as such`() {
        val wallet = FakeArkWallet()
        val vm = viewModel()
        state.value = ArkWalletState.Open(wallet, ArkRecovery.NotRun)
        var fee: com.machankura.dedun.compose.wallet.ark.ArkFee? = com.machankura.dedun.compose.wallet.ark.ArkFee(Satoshi(1), Satoshi(1), Satoshi(0))
        vm.renewalFee(onFee = { fee = it }, onFailure = {})
        assertEquals(null, fee)

        wallet.renewal = com.machankura.dedun.compose.wallet.ark.ArkFee(Satoshi(20_000), Satoshi(40), Satoshi(19_960))
        vm.renewalFee(onFee = { fee = it }, onFailure = {})
        assertEquals(Satoshi(40), fee?.fee)

        var started = false
        vm.renew(onStarted = { started = true }, onFailure = {})
        assertTrue(started)
        assertTrue("renew" in wallet.calls)
    }

    @Test
    fun `a read that fails is retried by reading, and an open that failed by reopening`() {
        val wallet = FakeArkWallet()
        wallet.failNext = "tipHeight" to ArkError.ServerUnreachable("no")
        val vm = viewModel()
        state.value = ArkWalletState.Open(wallet, ArkRecovery.NotRun)
        assertEquals(true, assertIs<ArkWalletUIState.Failed>(vm.uiState).opened)
        vm.retry()
        assertIs<ArkWalletUIState.Loaded>(vm.uiState)
        assertTrue(actions.isEmpty())

        state.value = ArkWalletState.Failed(ArkError.ServerUnreachable("no"))
        vm.retry()
        assertEquals(listOf("retry"), actions)
        vm.turnOn()
        assertEquals(listOf("retry", "consent"), actions)
    }
}
