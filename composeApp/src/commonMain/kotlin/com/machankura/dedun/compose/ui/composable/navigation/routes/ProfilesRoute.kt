package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * The profiles on this device: the one that is open, the others, and the two ways to
 * add one. Pushed from the profile tab; no arguments, since everything it shows is the
 * device's rather than a route's. See docs/multiple-profiles.md, Phase 4.
 */
@Serializable
object ProfilesRoute : Route()
