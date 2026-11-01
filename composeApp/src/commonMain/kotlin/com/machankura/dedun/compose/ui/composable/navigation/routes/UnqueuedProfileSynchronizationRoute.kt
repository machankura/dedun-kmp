package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

@Serializable
data class UnqueuedProfileSynchronizationRoute(
    val publicKey: String,
    val unsignedNostrEventId: Long,
): Route()