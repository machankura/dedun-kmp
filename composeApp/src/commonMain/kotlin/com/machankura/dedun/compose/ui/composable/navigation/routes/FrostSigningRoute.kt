package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * One signing session.
 *
 * Carries a session id rather than only a room, because a group signs
 * repeatedly and can have more than one session open at a time -- unlike a
 * ceremony, where "the room's ritual" identifies it.
 *
 * The id is required. It used to be optional, resolved to "the room's live
 * session" for callers that did not know which one they meant -- a transcript
 * line, mostly, since chat rows carried no session. Two proposals open at once
 * made that guess wrong, and every one of those lines now names its session; a
 * line too old to have one goes to [ProposalListRoute] instead, where the
 * reader picks from the whole list rather than being sent to a guess.
 */
@Serializable
data class FrostSigningRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    val sessionId: String
): Route()
