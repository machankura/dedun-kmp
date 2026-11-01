package com.machankura.dedun.compose.ui.view.state

sealed interface SovereignWalletUIState {
    data object Uninitialized: SovereignWalletUIState

    data class Success(
        val publicKey: String,
        val balanceInSatoshis: Long,
    ): SovereignWalletUIState


    data object Error: SovereignWalletUIState
    data object Loading: SovereignWalletUIState
}

