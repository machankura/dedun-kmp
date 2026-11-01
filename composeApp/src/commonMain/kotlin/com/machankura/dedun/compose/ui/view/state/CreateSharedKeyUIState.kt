package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom

sealed interface CreateSharedKeyUIState {
    /** The room, and how many people a ceremony in it would be split across. */
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        val participantCount: Int,
    ) : CreateSharedKeyUIState

    /**
     * The group already has a ceremony that has not failed -- running, or finished
     * with a key. The screen hands over to it rather than offering a second: a
     * second is another participant set for every member to reconcile, and the key
     * the first produced would be left with nothing pointing at it.
     */
    data class Existing(val dkgSessionId: String) : CreateSharedKeyUIState

    /** This room cannot hold a ceremony of its own, and the screen says why. */
    data class Refused(val reason: Reason) : CreateSharedKeyUIState {
        enum class Reason {
            /**
             * A Marmot room's id is derived from the key it already has, so a key
             * of its own is one it could never answer to. It hosts its subgroups'
             * ceremonies, which are started from the subgroup picker.
             */
            MARMOT_ROOM,

            /** A key held by one person is just a key. */
            TOO_FEW_MEMBERS,
        }
    }

    data class Error(val reason: Reason) : CreateSharedKeyUIState {
        enum class Reason { ROOM_NOT_FOUND, NO_KEYS, NOT_STARTED }
    }

    data object Loading : CreateSharedKeyUIState
}
