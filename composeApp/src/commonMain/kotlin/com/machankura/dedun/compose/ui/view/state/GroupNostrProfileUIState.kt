package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom
import com.machankura.dedun.compose.nostr.GroupNostrProfile

sealed interface GroupNostrProfileUIState {
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        /**
         * The kind:0 the group signed about itself, or null while it has signed
         * none.
         *
         * Null is an ordinary state and the screen says so -- a group has an
         * identity from the moment it has a key and describes it whenever
         * somebody gets round to it.
         */
        val groupNostrProfile: GroupNostrProfile? = null,
        /**
         * Whether this device holds a share of the group's key.
         *
         * False leaves the profile readable and hides the way into the editor:
         * proposing one is a signature by the group, and
         * `FrostSigningManager.proposeSigningBatch` throws for a device holding
         * no share. It is not about permission -- any member with a share may
         * propose -- and the quorum is what decides.
         */
        val canSign: Boolean = false,
    ): GroupNostrProfileUIState

    data class Error(
        val message: String
    ): GroupNostrProfileUIState

    data object Loading: GroupNostrProfileUIState
}
