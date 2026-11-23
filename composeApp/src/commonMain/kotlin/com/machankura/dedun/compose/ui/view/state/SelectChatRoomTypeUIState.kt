package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.Profile
import com.machankura.dedun.compose.managers.MarmotGroupName

sealed interface SelectChatRoomTypeUIState {
    data class Loaded(
        /** The picked members, for naming them back to the user. */
        val members: List<Profile>,
        /**
         * The groups these same people have already made together, newest first.
         *
         * Said rather than refused: the same people may make as many groups as they
         * like, and each one is a key and a room of its own. It is here for the user
         * who meant to open one of these and started making another instead.
         * docs/nip17-multiple-ceremonies.md, Phase 2.
         */
        val existingGroups: List<ExistingGroup> = emptyList(),
    ): SelectChatRoomTypeUIState {
        /**
         * Whether the group being made would share its name with one of [existingGroups]:
         * compared bare, so a room still stored under an old mark (`&Ekklesia`) is the same
         * name as a typed `Ekklesia` (docs/group-name-braces.md, D8).
         */
        fun sharesNameWith(name: String): Boolean =
            existingGroups.any { it.name?.let(MarmotGroupName::bare)?.equals(MarmotGroupName.bare(name), ignoreCase = true) == true }
    }

    /** A group already made by the same people: its name, or null if it has none. */
    data class ExistingGroup(val name: String?)

    data class Error(
        val message: String
    ): SelectChatRoomTypeUIState

    data object Loading: SelectChatRoomTypeUIState
}
