package com.machankura.dedun.compose.ui.view.state

import com.machankura.dedun.compose.repository.AdminRoom
import com.vitorpamplona.quartz.nip01Core.core.HexKey

/**
 * The screen that says why one member row action is not open.
 * docs/member-row-actions.md, Phase 4.
 */
sealed interface MemberActionUnavailableUIState {
    data object Loading : MemberActionUnavailableUIState

    /** The room could not be read; asking again is the retry. */
    data object Error : MemberActionUnavailableUIState

    /**
     * The room, the member, or the action's fit with the member's role has gone
     * since the row was opened: a member made an admin is not "made an admin" again.
     */
    data object NoLongerApplies : MemberActionUnavailableUIState

    data class Blocked(
        val memberName: String,
        val action: MemberAction,
        /** First the one the screen leads with, then the rest. Never empty. */
        val blockers: List<MemberActionBlocker>,
        /** What to call everyone a blocker names: admins to ask, devices waited on. */
        val names: Map<HexKey, String> = emptyMap(),
        /**
         * The admin rooms a NIP-17 room's members run their groups from, when known:
         * one per group the same people have made there.
         */
        val adminRooms: List<AdminRoom> = emptyList(),
    ) : MemberActionUnavailableUIState

    /** Nothing stands in the way any more: the blocker cleared while the screen was open. */
    data class NowAvailable(
        val memberName: String,
        val action: MemberAction,
    ) : MemberActionUnavailableUIState
}
