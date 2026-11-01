package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * Asking a group to guard a wallet only the active member can spend from --
 * `docs/member-guarded-wallets.md`, Phase 6. With [chatRoomId] the group is
 * already chosen; without it the screen offers the groups this profile is in.
 */
@Serializable
data class NewMemberWalletRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String? = null,
): Route()
