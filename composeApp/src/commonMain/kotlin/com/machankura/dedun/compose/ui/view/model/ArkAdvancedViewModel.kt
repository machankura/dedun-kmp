package com.machankura.dedun.compose.ui.view.model

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.machankura.dedun.compose.wallet.ark.ArkError
import com.machankura.dedun.compose.wallet.ark.ArkExitFee
import com.machankura.dedun.compose.wallet.ark.ArkExitStatus
import com.machankura.dedun.compose.wallet.ark.ArkFee
import com.machankura.dedun.compose.wallet.ark.ArkVtxo
import com.machankura.dedun.compose.wallet.ark.ArkWallet
import com.machankura.dedun.compose.wallet.ark.ArkWalletState
import com.machankura.dedun.compose.wallet.ark.IdentityArk
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The advanced screen -- `docs/bark-wallet.md`, Phase 9. */
sealed interface ArkAdvancedUIState {
    data object NoWallet : ArkAdvancedUIState

    data object Loading : ArkAdvancedUIState

    data class Loaded(
        val vtxos: List<ArkVtxo>,
        val exits: List<ArkExitStatus>,
        val exitFee: ArkExitFee?,
        val claimableAt: Long?,
        val tip: Long,
        /** Where to send onchain money for the exit's fees. */
        val fundingAddress: String,
    ) : ArkAdvancedUIState {
        val exiting: Boolean get() = exits.isNotEmpty()
        val claimable: Boolean get() = exits.any { it.state == ArkExitStatus.State.Claimable } && (claimableAt == null || tip >= claimableAt)
    }

    data class Failed(val error: ArkError) : ArkAdvancedUIState
}

/**
 * Leaving Ark: a cooperative offboard of everything, and the emergency exit for when the
 * server will not cooperate. Opening the screen progresses any exit under way, because
 * an exit only moves when somebody asks it to.
 */
class ArkAdvancedViewModel(
    private val walletState: StateFlow<ArkWalletState>,
) : ViewModel() {
    val addressField = TextFieldState()

    var uiState: ArkAdvancedUIState by mutableStateOf(ArkAdvancedUIState.Loading)
        private set

    /** The offboard's fee, for the address typed, once asked for. */
    var offboardFee: ArkFee? by mutableStateOf(null)
        private set

    private val wallet: ArkWallet? get() = (walletState.value as? ArkWalletState.Open)?.wallet

    fun load() {
        val open = wallet
        if (open == null) {
            uiState = ArkAdvancedUIState.NoWallet
            return
        }
        viewModelScope.launch {
            uiState = try {
                val exits = open.progressExits()
                ArkAdvancedUIState.Loaded(
                    vtxos = open.vtxos(),
                    exits = exits,
                    exitFee = if (exits.isEmpty()) open.emergencyExitFee() else null,
                    claimableAt = open.exitsClaimableAt(),
                    tip = open.tipHeight(),
                    fundingAddress = open.onchainAddress(),
                )
            } catch (e: ArkError) {
                ArkAdvancedUIState.Failed(e)
            }
        }
    }

    private fun address(): String? = addressField.text.toString().trim().ifEmpty { null }

    fun estimateOffboard(onFailure: () -> Unit) {
        val open = wallet ?: return
        val address = address() ?: return
        viewModelScope.launch {
            offboardFee = try { open.offboardAllFee(address) } catch (e: ArkError) { onFailure(); null }
        }
    }

    fun offboardAll(onStarted: () -> Unit, onFailure: () -> Unit) = act(onStarted, onFailure) { open ->
        open.offboardAll(address() ?: throw ArkError.InvalidRequest("no address"))
    }

    fun startEmergencyExit(onStarted: () -> Unit, onFailure: () -> Unit) = act(onStarted, onFailure) { it.startEmergencyExit() }

    fun claim(onStarted: () -> Unit, onFailure: () -> Unit) = act(onStarted, onFailure) { open ->
        open.claimExits(address() ?: throw ArkError.InvalidRequest("no address"))
    }

    private fun act(onStarted: () -> Unit, onFailure: () -> Unit, action: suspend (ArkWallet) -> Unit) {
        val open = wallet ?: return
        viewModelScope.launch {
            try {
                action(open)
                onStarted()
                load()
            } catch (e: ArkError) {
                onFailure()
            }
        }
    }

    companion object {
        fun factory(identityArk: IdentityArk): ViewModelProvider.Factory = viewModelFactory {
            initializer { ArkAdvancedViewModel(identityArk.state) }
        }
    }
}
