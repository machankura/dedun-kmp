package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/** The profile's own Ark wallet: its balance and history. `docs/bark-wallet.md`, Phase 4. */
@Serializable
data class ArkWalletRoute(
    val activeUserPublicKey: String,
): Route()

/** One payment in the profile's Ark wallet, below the expanded breakpoint. */
@Serializable
data class ArkMovementRoute(
    val activeUserPublicKey: String,
    val movementId: Long,
): Route()

/** Being paid into the profile's Ark wallet. `docs/bark-wallet.md`, Phase 5. */
@Serializable
data class ArkReceiveRoute(
    val activeUserPublicKey: String,
): Route()

/** Paying from the profile's Ark wallet. `docs/bark-wallet.md`, Phase 6. */
@Serializable
data class ArkSendRoute(
    val activeUserPublicKey: String,
): Route()

/** Leaving Ark: offboard and emergency exit. `docs/bark-wallet.md`, Phase 9. */
@Serializable
data class ArkAdvancedRoute(
    val activeUserPublicKey: String,
): Route()
