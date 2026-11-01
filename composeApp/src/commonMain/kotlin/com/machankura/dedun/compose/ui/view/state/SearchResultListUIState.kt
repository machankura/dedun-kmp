package com.machankura.dedun.compose.ui.view.state

sealed interface SearchResultListUIState {
    data class Loaded(
        val localNostrEvents: List<com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent>
    ): SearchResultListUIState

    data object Error: SearchResultListUIState
    data object Loading: SearchResultListUIState
}

