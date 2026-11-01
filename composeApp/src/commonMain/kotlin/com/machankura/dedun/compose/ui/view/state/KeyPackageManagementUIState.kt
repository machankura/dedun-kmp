package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.MarmotKeyPackageBundle

sealed interface KeyPackageManagementUIState {
    data class Loaded(
        val keyPackageBundles: List<MarmotKeyPackageBundle>
    ): KeyPackageManagementUIState

    data object Error: KeyPackageManagementUIState
    data object NotFound: KeyPackageManagementUIState
    data object Loading: KeyPackageManagementUIState
}

