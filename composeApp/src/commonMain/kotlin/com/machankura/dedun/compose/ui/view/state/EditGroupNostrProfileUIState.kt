package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.GroupNostrProfile

sealed interface EditGroupNostrProfileUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        /**
         * What the group has already said about itself, or null the first time.
         *
         * The form starts from it, and so does the event: an edit is built with
         * `MetadataEvent.updateFromPast` so a field this app does not offer survives
         * being edited rather than being dropped by it. See
         * [GroupNostrProfile.template].
         */
        val groupNostrProfile: GroupNostrProfile? = null,
        /**
         * Whether this device holds a share of the group's key.
         *
         * False leaves the form readable and the button inert, which is the honest
         * shape: the profile is worth reading either way, and a member without a
         * share cannot open a session for one. See
         * `ChatRoomDetailUIState.canProposeAsGroup`.
         */
        val canSign: Boolean = false,
    ): EditGroupNostrProfileUIState

    data class Error(
        val message: String
    ): EditGroupNostrProfileUIState

    data object Loading: EditGroupNostrProfileUIState
}
