package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * Starting a NIP-17 group's own key ceremony, and nothing else.
 *
 * The one place a group that has no key yet asks for one. Group creation and
 * the subgroup picker start their own ceremonies because they already know what
 * each is for; this is for a group that exists and has none. The ceremony screen
 * that follows only ever shows a ceremony -- it used to offer to start one too,
 * from the same button the approvals used, which is how a member joining a
 * subgroup's ceremony opened a second. See docs/ceremony-creation.md.
 */
@Serializable
data class CreateSharedKeyRoute(
    val activeUserPublicKey: String,
    val chatRoomId: String,
): Route()
