package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * Proposing a payment from the group's Lightning wallet, from the Lightning wallet screen -- or, with
 * [invoice], from a member's payout sheet, the invoice already fetched from their Lightning address and
 * [payoutMember]'s destination [payoutDestination] named on the proposal
 * (`docs/group-payout-destination.md`, Phase 7).
 */
@Serializable
data class GroupLightningSendRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    val invoice: String? = null,
    val payoutMember: String? = null,
    val payoutDestination: String? = null,
) : Route()
