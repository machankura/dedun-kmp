package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable
import com.machankura.dedun.compose.ui.view.state.ProposalFilter

/**
 * Everything a group has asked its shared key to sign.
 *
 * A room's own list, not the reader's: a proposal is put to the whole group, and
 * which of them are waiting on this member is a status on each row rather than a
 * different list. See [FrostSigningRoute] for one of them.
 */
@Serializable
data class ProposalListRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,

    /**
     * Which of them to open on, for the callers that already know.
     *
     * The transcript's notice counts the proposals waiting on this member and
     * says so; a tap on it that then showed the room's whole history would have
     * the reader find the three it just named for themselves. So the notice asks
     * for [ProposalFilter.NEEDS_YOU] and arrives with the chip already set.
     *
     * [ProposalFilter.ALL] is the honest default, and is what the ways in that
     * mean "show me the room" pass -- the group's own Proposals row, and the
     * transcript lines that cannot say which proposal they are about.
     */
    val filter: ProposalFilter = ProposalFilter.ALL,
): Route()
