package com.machankura.dedun.compose.ui.composable.navigation.routes

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.ui.view.state.ProposalFilter

/**
 * Where tapping a proposal in the transcript goes.
 *
 * Three answers, and they are three because the tap means three different
 * things. Pulled out of the screen so each can be stated once and checked: the
 * decision is made from two facts and a null, and a `when` buried in a lambda
 * nested nine deep inside a `LazyColumn` is not a thing anybody can hold to it.
 *
 * @param sessionId the proposal the tapped line names, or null when it predates
 *   chat rows naming their session at all.
 * @param hasOtherDecisionsWaiting whether opening that proposal by itself would
 *   hide other decisions the room is waiting on this member for -- see
 *   `ChatMessageListViewModel.hidesOtherDecisions`, which is what the screen
 *   passes.
 */
fun proposalDestination(
    activeUserPublicKey: HexKey,
    chatRoomId: String,
    sessionId: String?,
    hasOtherDecisionsWaiting: (String) -> Boolean,
): Route = when {
    // A line too old to say which proposal it is about. The list is the honest
    // answer -- every proposal with its own state, rather than a guess at one --
    // and it is the room's list, unfiltered, because a line like this could be
    // about anything the group has ever signed.
    sessionId == null -> ProposalListRoute(
        activeUserPublicKey = activeUserPublicKey,
        chatRoomId = chatRoomId
    )

    // Several decisions are waiting and this is one of them. Opening the one they
    // tapped would have answering it look like being done, so they get the queue
    // -- and the queue is what the filter is for. Showing the room's whole
    // history here would be the same mistake in the other direction: it was the
    // other decisions, not the history, that the one proposal was held back for.
    hasOtherDecisionsWaiting(sessionId) -> ProposalListRoute(
        activeUserPublicKey = activeUserPublicKey,
        chatRoomId = chatRoomId,
        filter = ProposalFilter.NEEDS_YOU
    )

    // One proposal, named, and nothing else being asked. Open it.
    else -> FrostSigningRoute(
        activeUserPublicKey = activeUserPublicKey,
        chatRoomId = chatRoomId,
        sessionId = sessionId
    )
}
