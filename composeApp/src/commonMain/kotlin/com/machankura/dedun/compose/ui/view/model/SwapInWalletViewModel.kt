package com.machankura.dedun.compose.ui.view.model

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.identity.Identity
import com.machankura.dedun.compose.ui.view.state.SwapInWalletUIState
import com.machankura.dedun.compose.text.SatsInput
import com.machankura.dedun.compose.text.parseSats
import com.machankura.dedun.compose.wallet.NodeFeeLimit
import com.machankura.dedun.compose.wallet.PeerSwapInSource
import com.machankura.dedun.compose.wallet.SwapInFeeLimit
import com.machankura.dedun.compose.wallet.SwapInFeeLimits
import com.machankura.dedun.compose.wallet.SwapInRefusal
import com.machankura.dedun.compose.wallet.SwapInAddress
import com.machankura.dedun.compose.wallet.SwapInNotice
import com.machankura.dedun.compose.wallet.SwapInSnapshot
import com.machankura.dedun.compose.wallet.SwapInSource
import com.machankura.dedun.compose.wallet.SwapInWaiting
import com.machankura.dedun.compose.wallet.SwapInWalletView
import fr.acinq.bitcoin.Satoshi
import fr.acinq.lightning.payment.LiquidityPolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * The personal node's swap-in wallet, read for as long as the screen is open
 * (docs/personal-swap-in-wallet.md, Phase 2).
 *
 * [source] is null for a profile with no node. A profile switch clears the identity, which sends the
 * navigation back to startup and pops this screen, so the collection ends with the node it was reading.
 */
class SwapInWalletViewModel(
    private val source: SwapInSource?,
    /** Null where nothing can change the node's fee limit: a profile with no node, and the tests that read only. */
    private val feeLimit: SwapInFeeLimit? = null,
) : ViewModel() {
    private val log = Logger.withTag("SwapInWalletViewModel")

    var uiState: SwapInWalletUIState by mutableStateOf(if (source == null) SwapInWalletUIState.NoNode else SwapInWalletUIState.Loading)
        private set

    private var collecting: Job? = null
    private var notifying: Job? = null

    private val _notices = Channel<SwapInNotice>(Channel.BUFFERED)

    /** What the node just did with the deposits, each once, for the screen to show as a snackbar. */
    val notices: Flow<SwapInNotice> = _notices.receiveAsFlow()

    /** The refusal whose fee the fee-limit sheet is reviewing, or null while it is closed (Phase 5). */
    var reviewing: SwapInRefusal? by mutableStateOf(null)
        private set

    /** Whether the sheet can change anything: only over a node, and only for a refusal a higher fee clears. */
    val canReviewFees: Boolean get() = feeLimit != null

    /** The limit the user types, offered at the refused fee plus the margin. */
    val limitField = TextFieldState()

    /** While the node is being given a new policy: the sheet's buttons wait. */
    var isApplying: Boolean by mutableStateOf(false)
        private set

    fun review(refusal: SwapInRefusal) {
        val fee = when (refusal) {
            is SwapInRefusal.OverLimit -> refusal.fee
            is SwapInRefusal.OverShare -> refusal.fee
            else -> return
        }
        reviewing = refusal
        limitField.setTextAndPlaceCursorAtEnd(SwapInFeeLimits.withMargin(fee).sat.toString())
    }

    fun dismissReview() {
        reviewing = null
    }

    /** Why the typed limit cannot be saved, or null when it can. */
    sealed interface LimitProblem {
        data class Input(val input: SatsInput) : LimitProblem
        data object Zero : LimitProblem
        /** More than the coins being moved: a fee that size would take all of them. */
        data class OverAmount(val amount: Satoshi) : LimitProblem
    }

    fun limitProblem(): LimitProblem? {
        val amount = (reviewing as? SwapInRefusal.OverLimit)?.amount
        return when (val input = parseSats(limitField.text)) {
            is SatsInput.Sats -> when {
                input.amount == Satoshi(0) -> LimitProblem.Zero
                amount != null && input.amount > amount -> LimitProblem.OverAmount(amount)
                else -> null
            }
            else -> LimitProblem.Input(input)
        }
    }

    /** Raises the running node's limit just enough for the fee under review, until it moves (D6). */
    fun allowOnce(onDone: () -> Unit) {
        val feeLimit = feeLimit ?: return
        val refusal = reviewing ?: return
        applying(onDone) {
            val saved = feeLimit.saved() as? LiquidityPolicy.Auto ?: return@applying false
            val raised = SwapInFeeLimits.allowingOnce(saved, refusal) ?: return@applying false
            feeLimit.allowOnce(raised)
            true
        }
    }

    /** Saves the typed limit as the user's from now on. */
    fun saveLimit(onDone: () -> Unit) {
        val feeLimit = feeLimit ?: return
        if (limitProblem() != null) return
        val limit = (parseSats(limitField.text) as SatsInput.Sats).amount
        applying(onDone) {
            val saved = feeLimit.saved() as? LiquidityPolicy.Auto ?: return@applying false
            feeLimit.save(SwapInFeeLimits.withLimit(saved, limit))
            true
        }
    }

    private fun applying(onDone: () -> Unit, change: suspend () -> Boolean) {
        if (isApplying) return
        isApplying = true
        viewModelScope.launch {
            val changed = runCatching { change() }
                .onFailure { log.e("could not change the fee limit", it) }
                .getOrDefault(false)
            isApplying = false
            if (changed) {
                reviewing = null
                onDone()
            }
        }
    }

    /** Starts reading the node, or reads it again after an error. Does nothing while already reading. */
    fun initiate() {
        val source = source ?: return
        if (collecting?.isActive == true) return
        uiState = SwapInWalletUIState.Loading
        // Apart from the snapshots, so that a failed read leaves nothing running to block its retry.
        if (notifying?.isActive != true) {
            notifying = viewModelScope.launch {
                source.notices().catch { e -> log.e("could not read the node's events", e) }.collect { _notices.send(it) }
            }
        }
        collecting = viewModelScope.launch {
            source.snapshots()
                .catch { e ->
                    log.e("could not read the swap-in wallet", e)
                    uiState = SwapInWalletUIState.Error
                }
                .collect { snapshot -> uiState = stateOf(snapshot) }
        }
    }

    companion object {
        /** Loading until both the address list and the tip are known; then everything at once. */
        fun stateOf(snapshot: SwapInSnapshot): SwapInWalletUIState {
            val addresses = SwapInWalletView.addresses(snapshot.state, snapshot.legacyAddress) ?: return SwapInWalletUIState.Loading
            val tip = snapshot.tip ?: return SwapInWalletUIState.Loading
            val current = addresses.firstOrNull { it.status == SwapInAddress.Status.CURRENT } ?: return SwapInWalletUIState.Loading
            val coins = SwapInWalletView.coins(snapshot.state, tip, snapshot.params, snapshot.reserved)
            return SwapInWalletUIState.Loaded(
                current = current,
                used = addresses.filter { it.status == SwapInAddress.Status.USED },
                coins = coins,
                minConfirmations = snapshot.params.minConfirmations,
                firstDeposit = SwapInWalletView.firstDeposit(snapshot.hasChannel, snapshot.openingFee, snapshot.policy),
                waiting = SwapInWaiting.of(coins, snapshot.connected, snapshot.refusal),
            )
        }

        fun factory(identity: Identity?): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val business = identity?.business
                SwapInWalletViewModel(
                    source = business?.let { PeerSwapInSource(it.peerManager, it.notificationsManager) },
                    feeLimit = business?.let { NodeFeeLimit.of(it.peerManager, identity.userPrefs) },
                )
            }
        }
    }
}
