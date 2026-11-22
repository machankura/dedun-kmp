package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.database.model.DkgSession
import com.machankura.dedun.compose.database.model.intermdiate.LocalChatRoom

sealed interface CreateSharedKeyUIState {
    /**
     * The room, how many people a ceremony in it would be split across, and the
     * groups its members have already made there.
     *
     * [groups] is every own ceremony the room holds that has not failed, newest
     * first. A room with any has to name the next one: it keeps its first group's
     * name, and a second group named by it would be that one's namesake
     * (docs/nip17-multiple-ceremonies.md, D2). It used to be the reason to hand over
     * to the room's ceremony instead of offering one; the same people may make as
     * many groups as they like now, so it is what the screen tells them they have.
     */
    data class Loaded(
        val localChatRoom: LocalChatRoom,
        val participantCount: Int,
        val groups: List<DkgSession> = emptyList(),
    ) : CreateSharedKeyUIState {
        /** Whether the group being made has to be given a name of its own. */
        val needsName: Boolean get() = groups.isNotEmpty()
    }

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
