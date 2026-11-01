package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/** The nostr secret key behind a bare-key identity: shown on request, and the way to forget it. */
@Serializable
data object NostrSecretRoute : Route()
