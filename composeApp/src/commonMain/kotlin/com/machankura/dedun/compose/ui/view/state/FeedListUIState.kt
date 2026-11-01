package com.machankura.dedun.compose.ui.view.state

sealed interface FeedListUIState {
    data class Loaded(
        val localNostrEvents: List<com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent>
    ): FeedListUIState

    data object Error: FeedListUIState
    data object Loading: FeedListUIState
}

