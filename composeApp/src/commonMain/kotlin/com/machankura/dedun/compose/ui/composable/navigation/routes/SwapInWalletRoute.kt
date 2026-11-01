package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * The personal node's swap-in wallet: the address to deposit to, the coins sent there and where each
 * is on its way into Lightning -- pushed from the profile screen (docs/personal-swap-in-wallet.md).
 */
@Serializable
data object SwapInWalletRoute : Route()
