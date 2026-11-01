package com.machankura.dedun.compose.ui.view.state

abstract class CreateProfileUIState {
    data object Error: CreateProfileUIState()

    data object Declaration: CreateProfileUIState()

    data object InputPrompt: CreateProfileUIState()

    data class ConfirmInput(
        val name: String,
        val bio: String
    ) : CreateProfileUIState()

    data class ProfileReady(
        val profile: com.machankura.dedun.compose.database.model.Profile
    ) : CreateProfileUIState()
}