package com.machankura.dedun.compose.ui.view.state

sealed interface ActiveProfileUIState {
    data class Loaded(
        val localNostrEvent: com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent
    ): ActiveProfileUIState

    data object Error: ActiveProfileUIState
    data object NotFound: ActiveProfileUIState
    data object Loading: ActiveProfileUIState
}

