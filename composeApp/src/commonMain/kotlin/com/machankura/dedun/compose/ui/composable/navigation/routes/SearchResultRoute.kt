package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

@Serializable
data class SearchResultRoute(
    val activeUserPublicKey: String,
    val query: String
): Route()