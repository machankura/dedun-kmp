package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * Final step of group creation: how the group should be run. Everything the
 * earlier steps collected rides along, because the governance choice feeds the
 * epoch-0 group context and so nothing can be persisted before it is made.
 */
@Serializable
data class SelectChatRoomTypeRoute(
    val activeUserPublicKey: String,
    val name: String,
    val description: String? = null,
    val memberPublicKeys: List<String> = emptyList()
): Route()
