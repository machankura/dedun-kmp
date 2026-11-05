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

/** Paying from the profile's wallet. `docs/wavelength-wallet.md`, Phase 7. */
@Serializable
data class WavelengthSendRoute(
    val activeUserPublicKey: String,
): Route()

/** Leaving Ark: with the server's help and without it. `docs/wavelength-wallet.md`, Phase 9. */
@Serializable
data class WavelengthAdvancedRoute(
    val activeUserPublicKey: String,
): Route()

/** The words of a wallet that has its own: an nsec profile's. `docs/wavelength-wallet.md`, D13. */
@Serializable
data class WavelengthRecoveryPhraseRoute(
    val activeUserPublicKey: String,
): Route()
