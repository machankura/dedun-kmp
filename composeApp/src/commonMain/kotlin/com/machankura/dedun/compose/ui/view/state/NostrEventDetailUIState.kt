package com.machankura.dedun.compose.ui.view.state

sealed interface NostrEventDetailUIState {
    data class Loaded(
        val localNostrEvent: com.machankura.dedun.compose.database.model.intermdiate.LocalNostrEvent
    ): NostrEventDetailUIState

    data object Error: NostrEventDetailUIState
    data object NotFound: NostrEventDetailUIState
    data object Loading: NostrEventDetailUIState
}

