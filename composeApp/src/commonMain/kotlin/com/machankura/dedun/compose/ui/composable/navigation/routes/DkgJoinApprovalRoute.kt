package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * Joining a shared-key ceremony somebody else opened.
 *
 * Its own route rather than a step on a shared one: each approval is a separate
 * decision with its own case to make, and being separately addressable is what
 * lets the chat line and the ritual screen send a member straight to the one the
 * ceremony is actually waiting on.
 */
@Serializable
data class DkgJoinApprovalRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    /** The ceremony being approved. Required, for the reason `DkgRitualRoute`'s is. */
    val dkgSessionId: String
): Route()
