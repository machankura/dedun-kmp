package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.repository.PayoutDestinationRepository

sealed interface GroupPayoutDestinationUIState {
    data object Loading : GroupPayoutDestinationUIState

    data class Error(val message: String) : GroupPayoutDestinationUIState

    data class Loaded(val standing: PayoutDestinationRepository.Standing) : GroupPayoutDestinationUIState
}
