package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * The Iceberg key ceremony a group's Lightning wallet signs with: watch it, approve this device's
 * steps, or start one. Pushed from a transcript line, which names its ceremony, or from the Lightning
 * wallet screen, which passes null and gets the room's newest.
 */
@Serializable
data class IcebergCeremonyRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    val sessionId: String? = null,
) : Route()
