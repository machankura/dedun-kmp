package com.machankura.dedun.compose.ui.view.state

sealed interface FollowersListUIState {
    data class Loaded(
        val followers: List<com.machankura.dedun.compose.database.model.Profile>
    ): FollowersListUIState

    data object Error: FollowersListUIState
    data object Loading: FollowersListUIState
}

