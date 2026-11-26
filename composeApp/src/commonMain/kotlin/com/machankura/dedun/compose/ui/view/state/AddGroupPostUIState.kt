package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.GroupNostrProfile

sealed interface AddGroupPostUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        /**
         * Who the group says it is, for the byline over the draft.
         *
         * A post is signed by the group and read as the group, so the composer
         * shows whose voice is being written in -- which is not the member typing.
         * Null in a group that has not described itself, and the byline falls back
         * to the room.
         */
        val groupNostrProfile: GroupNostrProfile? = null,
        /**
         * Whether this device holds a share of the group's key.
         *
         * False leaves the draft writable and the button inert, for the same reason
         * the other two editors do: reading and composing cost nothing, and only a
         * share-holder can open the session that would sign it.
         */
        val canSign: Boolean = false,
    ): AddGroupPostUIState

    data class Error(
        val message: String
    ): AddGroupPostUIState

    data object Loading: AddGroupPostUIState
}
