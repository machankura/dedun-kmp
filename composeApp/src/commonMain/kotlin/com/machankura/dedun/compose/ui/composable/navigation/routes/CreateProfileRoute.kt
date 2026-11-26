package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * @param withWallet whether the profile has a wallet attached, backed by a recovery code, or
 *   is a bare key. True from Landing, where the first profile on a device is the one the
 *   wallet will belong to, and since docs/giza-second-batch.md, Phase 2f, the code is its
 *   backup; false from the switcher, where wanting another profile is not wanting another
 *   wallet, or another thing to write down. See docs/multiple-profiles.md, Phase 5.
 */
@Serializable
data class CreateProfileRoute(
    val withWallet: Boolean,
): Route()
