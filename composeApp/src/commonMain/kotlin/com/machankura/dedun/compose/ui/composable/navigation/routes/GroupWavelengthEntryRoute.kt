package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * One thing a group's Wavelength wallet did, in full. Pushed at every width rather than taking a pane: the
 * wallet screen already uses both above the expanded breakpoint, and the right one holds who runs the
 * wallet, how many signers have been seen and what the daemon is doing -- which is the explanation for
 * most of what a member opens a detail to ask about (`docs/group-wavelength-transaction-details.md`, T7).
 *
 * [entryId] is `GroupWavelengthEntry.id`, matched against the history and never parsed back into a key to
 * query with (T4).
 */
@Serializable
data class GroupWavelengthEntryRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    val chain: String,
    val entryId: String,
) : Route()
