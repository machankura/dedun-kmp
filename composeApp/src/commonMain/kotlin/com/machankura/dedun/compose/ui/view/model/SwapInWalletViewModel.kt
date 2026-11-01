package com.machankura.dedun.compose.ui.view.model

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
import com.machankura.dedun.compose.wallet.PeerSwapInSource
import com.machankura.dedun.compose.wallet.SwapInAddress
import com.machankura.dedun.compose.wallet.SwapInSnapshot
import com.machankura.dedun.compose.wallet.SwapInSource
import com.machankura.dedun.compose.wallet.SwapInWalletView
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
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
) : ViewModel() {
    private val log = Logger.withTag("SwapInWalletViewModel")

    var uiState: SwapInWalletUIState by mutableStateOf(if (source == null) SwapInWalletUIState.NoNode else SwapInWalletUIState.Loading)
        private set

    private var collecting: Job? = null

    /** Starts reading the node, or reads it again after an error. Does nothing while already reading. */
    fun initiate() {
        val source = source ?: return
        if (collecting?.isActive == true) return
        uiState = SwapInWalletUIState.Loading
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
            return SwapInWalletUIState.Loaded(
                current = current,
                used = addresses.filter { it.status == SwapInAddress.Status.USED },
                coins = SwapInWalletView.coins(snapshot.state, tip, snapshot.params, snapshot.reserved),
                minConfirmations = snapshot.params.minConfirmations,
                firstDeposit = SwapInWalletView.firstDeposit(snapshot.hasChannel, snapshot.openingFee, snapshot.policy),
            )
        }

        fun factory(identity: Identity?): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SwapInWalletViewModel(source = identity?.business?.peerManager?.let(::PeerSwapInSource))
            }
        }
    }
}
