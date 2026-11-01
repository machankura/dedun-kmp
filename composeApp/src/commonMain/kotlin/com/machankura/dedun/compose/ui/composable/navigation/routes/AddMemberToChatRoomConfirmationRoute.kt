package com.machankura.dedun.compose.ui.composable.navigation.routes

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.serialization.Serializable

@Serializable
data class AddMemberToChatRoomConfirmationRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String, // TODO: have this as a publicKey
    val profilePublicKey: HexKey,
    val relayHint: String?
): Route()