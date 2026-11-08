package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * An invoice for paying the group's Wavelength wallet over Lightning, on [chain].
 *
 * A screen of its own rather than the second half of a chip pair (B4 of
 * `docs/group-wavelength-redesign.md`). The two rails do not differ in speed and fee -- they differ in
 * **who has to be trusted**: an address is one this device recomputed from the group's key, and an
 * invoice is one whoever runs the wallet made and no device can check. A chip pair put that choice
 * behind a toggle that looked like a preference.
 */
@Serializable
data class GroupWavelengthInvoiceRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    val chain: String,
) : Route()
