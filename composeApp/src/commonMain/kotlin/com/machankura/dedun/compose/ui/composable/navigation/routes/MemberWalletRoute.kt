package com.machankura.dedun.compose.ui.composable.navigation.routes

import kotlinx.serialization.Serializable

/**
 * One of the active member's guarded wallets: its balance, where to be paid,
 * its coins, what its guardians took, and -- while no group has certified it --
 * where the asking has got to. `docs/member-guarded-wallets.md`, Phase 8.
 */
@Serializable
data class MemberWalletRoute(
    val activeUserPublicKey: String,
    val walletId: String,
): Route()
