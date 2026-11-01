package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * Second step of group creation: the name/description gathered by
 * [ChatRoomCreationRoute] travel with the route so the group is only ever
 * created once the user has picked who is in it.
 */
@Serializable
data class SelectChatRoomMembersRoute(
    val activeUserPublicKey: String,
    val name: String,
    val description: String? = null
): Route()
