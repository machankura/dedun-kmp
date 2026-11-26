package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/** An identity's notification switches. docs/notifications-and-background-sync.md, Phase 7. */
@Serializable
data class NotificationSettingsRoute(
    val activeUserPublicKey: String,
): Route()
