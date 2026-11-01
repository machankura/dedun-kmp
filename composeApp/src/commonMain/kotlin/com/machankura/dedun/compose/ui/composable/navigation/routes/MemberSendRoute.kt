package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/** The form a member sends from their own guarded wallet with, signing alone. */
@Serializable
data class MemberSendRoute(
    val activeUserPublicKey: String,
    val walletId: String,
): Route()
