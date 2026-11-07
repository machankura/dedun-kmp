package com.machankura.dedun.compose.ui.composable.navigation.routes

import com.machankura.dedun.compose.database.model.IcebergKeySession
import kotlinx.serialization.Serializable

/**
 * The Iceberg key ceremony a group's wallet signs with: watch it, approve this device's steps, or start
 * one. Pushed from a transcript line, which names its ceremony, or from a wallet screen, which passes null
 * and gets the room's newest for [purpose], on [chain] (the node's own when null).
 */
@Serializable
data class IcebergCeremonyRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
    val sessionId: String? = null,
    val purpose: String = IcebergKeySession.PURPOSE_LIGHTNING,
    val chain: String? = null,
) : Route()
