package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

@Serializable
data class SearchRoute(
    val activeUserPublicKey: String,
): Route()