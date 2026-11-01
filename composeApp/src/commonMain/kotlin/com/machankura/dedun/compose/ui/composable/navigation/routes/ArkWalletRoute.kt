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
