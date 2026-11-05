package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/** The profile's own wallet: its balance and history. `docs/wavelength-wallet.md`, Phase 5. */
@Serializable
data class WavelengthWalletRoute(
    val activeUserPublicKey: String,
): Route()

/** One payment in the profile's wallet, below the expanded breakpoint. */
@Serializable
data class WavelengthEntryRoute(
    val activeUserPublicKey: String,
    val entryId: String,
): Route()

/** Being paid into the profile's wallet. `docs/wavelength-wallet.md`, Phase 6. */
@Serializable
data class WavelengthReceiveRoute(
    val activeUserPublicKey: String,
): Route()
