package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * @param withWallet whether the profile is made of a seed, with a wallet attached, or of
 *   a bare key. True from Landing, where the first profile on a device is the one the
 *   wallet will belong to; false from the switcher, where wanting another profile is not
 *   wanting another wallet. See docs/multiple-profiles.md, Phase 5.
 */
@Serializable
data class CreateProfileRoute(
    val withWallet: Boolean,
): Route()
