package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.GroupNostrProfile

sealed interface ProposeGroupEventUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        /**
         * Who the group says it is, for the byline over the paste.
         *
         * Whatever is pasted goes out signed by the group and read as the group,
         * so the screen shows whose name it will carry -- which is not the
         * member pasting it, and not whatever `pubkey` the paste may name.
         */
        val groupNostrProfile: GroupNostrProfile? = null,
        /**
         * Whether this device holds a share of the group's key.
         *
         * False leaves the paste writable and the button inert, for the same
         * reason the typed editors do: reading and checking cost nothing, and
         * only a share-holder can open the session that would sign it.
         */
        val canSign: Boolean = false,
    ): ProposeGroupEventUIState

    data class Error(
        val message: String
    ): ProposeGroupEventUIState

    data object Loading: ProposeGroupEventUIState
}
