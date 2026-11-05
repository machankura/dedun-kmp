package com.machankura.dedun.compose.ui.view.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.machankura.dedun.compose.wallet.wavelength.IdentityWavelength
import com.machankura.dedun.compose.wallet.wavelength.WalletState
import engineering.lightning.wavelength.ExitBatchOptions
import engineering.lightning.wavelength.ListRequest
import engineering.lightning.wavelength.SendRequest
import engineering.lightning.wavelength.SweepWalletRequest
import engineering.lightning.wavelength.WavelengthException
import engineering.lightning.wavelength.engine.WalletEngine
import engineering.lightning.wavelength.model.ExitSummaryEntry
import engineering.lightning.wavelength.model.GetExitPlanResult
import engineering.lightning.wavelength.model.ListView
import engineering.lightning.wavelength.model.PrepareSendResult
import engineering.lightning.wavelength.model.SweepWalletResult
import engineering.lightning.wavelength.model.WalletVTXO
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** A step of getting out that waits for a person to say yes. */
sealed interface WavelengthExitStep {
    /** Everything onchain with the server's help: the price, for the address typed. */
    data class MoveAll(val quote: PrepareSendResult) : WavelengthExitStep

    /** Without the server: what it would take. It cannot be undone, and may need funding first. */
    data class Emergency(val plan: GetExitPlanResult, val fundingAddress: String?) : WavelengthExitStep

    /** The onchain side, swept to an address: the price. */
    data class Sweep(val preview: SweepWalletResult) : WavelengthExitStep
}

enum class WavelengthAdvancedProblem { CouldNotRead, NeedsAddress, CouldNotMove, CouldNotExit, CouldNotSweep }

enum class WavelengthAdvancedNotice { MoveStarted, ExitStarted, ExitStopped, Swept }

/** What the advanced screen draws -- `docs/wavelength-wallet.md`, Phase 9. */
sealed interface WavelengthAdvancedUIState {
    data object NoWallet : WavelengthAdvancedUIState

    data object Loading : WavelengthAdvancedUIState

    data class Loaded(
        val coins: List<WalletVTXO>,
        val exits: List<ExitSummaryEntry>,
        val address: String = "",
        val working: Boolean = false,
        val step: WavelengthExitStep? = null,
        val problem: WavelengthAdvancedProblem? = null,
        val notice: WavelengthAdvancedNotice? = null,
    ) : WavelengthAdvancedUIState {
        val hasCoins: Boolean get() = coins.isNotEmpty()
    }
}

/**
 * Getting out. Every step is priced first and then confirmed, and the address is always typed
 * by the person: a wallet leaving Ark has nowhere it should guess at.
 *
 * Exits belong to the daemon, in its database, and carry on across restarts. This only starts them
 * and shows how they stand, which is why [load] is all it needs after one.
 */
class WavelengthAdvancedViewModel(private val walletState: StateFlow<WalletState>) : ViewModel() {
    var uiState: WavelengthAdvancedUIState by mutableStateOf(
        if (walletState.value is WalletState.Open) WavelengthAdvancedUIState.Loading else WavelengthAdvancedUIState.NoWallet,
    )
        private set

    private val engine: WalletEngine? get() = (walletState.value as? WalletState.Open)?.engine
    private val loaded: WavelengthAdvancedUIState.Loaded? get() = uiState as? WavelengthAdvancedUIState.Loaded

    fun load() {
        val engine = engine ?: return
        viewModelScope.launch {
            uiState = try {
                val coins = engine.list(ListRequest(ListView.VTXOS)).vtxos?.vtxos.orEmpty()
                val exits = engine.exitSummary().exits
                (loaded ?: WavelengthAdvancedUIState.Loaded(emptyList(), emptyList())).copy(coins = coins, exits = exits, working = false)
            } catch (e: WavelengthException) {
                (loaded ?: WavelengthAdvancedUIState.Loaded(emptyList(), emptyList())).copy(working = false, problem = WavelengthAdvancedProblem.CouldNotRead)
            }
        }
    }

    fun onAddressChange(text: String) {
        loaded?.let { uiState = it.copy(address = text.trim(), problem = null) }
    }

    fun dismissStep() {
        loaded?.let { uiState = it.copy(step = null) }
    }

    /** Prices moving everything onchain, with the server's help. */
    fun priceMoveAll() = withAddress(WavelengthAdvancedProblem.CouldNotMove) { engine, state ->
        val quote = engine.prepareSend(SendRequest.Onchain(state.address, sweepAll = true))
        state.copy(working = false, step = WavelengthExitStep.MoveAll(quote))
    }

    /** Asks what an exit without the server would take. Needs no address: the daemon's own wallet receives it. */
    fun planEmergency() = step(WavelengthAdvancedProblem.CouldNotExit) { engine, state ->
        val plan = engine.getExitPlan(state.coins.map { it.outpoint })
        val funding = if (plan.totalFundingShortfallSat > 0) plan.plans.firstOrNull { it.fundingAddress.isNotEmpty() }?.fundingAddress else null
        state.copy(working = false, step = WavelengthExitStep.Emergency(plan, funding))
    }

    /** Prices sweeping the onchain side to the address typed. */
    fun priceSweep() = withAddress(WavelengthAdvancedProblem.CouldNotSweep) { engine, state ->
        val preview = engine.sweepWallet(SweepWalletRequest(state.address))
        state.copy(working = false, step = WavelengthExitStep.Sweep(preview))
    }

    /** The person said yes to what [WavelengthAdvancedUIState.Loaded.step] says. */
    fun confirm() {
        val state = loaded ?: return
        when (val step = state.step) {
            is WavelengthExitStep.MoveAll -> act(WavelengthAdvancedProblem.CouldNotMove) { engine ->
                engine.sendPrepared(step.quote)
                WavelengthAdvancedNotice.MoveStarted
            }
            is WavelengthExitStep.Emergency -> act(WavelengthAdvancedProblem.CouldNotExit) { engine ->
                val result = engine.exitBatch(ExitBatchOptions.Unilateral(state.coins.map { it.outpoint }))
                if (result.stoppedBy != null) WavelengthAdvancedNotice.ExitStopped else WavelengthAdvancedNotice.ExitStarted
            }
            is WavelengthExitStep.Sweep -> act(WavelengthAdvancedProblem.CouldNotSweep) { engine ->
                engine.sweepWallet(SweepWalletRequest(state.address, broadcast = true))
                WavelengthAdvancedNotice.Swept
            }
            null -> Unit
        }
    }

    private fun act(failure: WavelengthAdvancedProblem, block: suspend (WalletEngine) -> WavelengthAdvancedNotice) {
        val engine = engine ?: return
        val state = loaded ?: return
        uiState = state.copy(working = true, step = null, problem = null, notice = null)
        viewModelScope.launch {
            try {
                val notice = block(engine)
                uiState = (loaded ?: state).copy(notice = notice)
                load()
            } catch (e: WavelengthException) {
                uiState = (loaded ?: state).copy(working = false, problem = failure)
            }
        }
    }

    private fun withAddress(failure: WavelengthAdvancedProblem, block: suspend (WalletEngine, WavelengthAdvancedUIState.Loaded) -> WavelengthAdvancedUIState.Loaded) {
        val state = loaded ?: return
        if (state.address.isEmpty()) {
            uiState = state.copy(problem = WavelengthAdvancedProblem.NeedsAddress)
            return
        }
        step(failure, block)
    }

    private fun step(failure: WavelengthAdvancedProblem, block: suspend (WalletEngine, WavelengthAdvancedUIState.Loaded) -> WavelengthAdvancedUIState.Loaded) {
        val engine = engine ?: return
        val state = loaded ?: return
        uiState = state.copy(working = true, problem = null, notice = null)
        viewModelScope.launch {
            uiState = try {
                block(engine, state)
            } catch (e: WavelengthException) {
                state.copy(working = false, problem = failure)
            }
        }
    }

    companion object {
        fun factory(identityWavelength: IdentityWavelength): ViewModelProvider.Factory = viewModelFactory {
            initializer { WavelengthAdvancedViewModel(identityWavelength.state) }
        }
    }
}
