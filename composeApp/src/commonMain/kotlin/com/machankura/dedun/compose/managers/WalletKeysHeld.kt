package com.machankura.dedun.compose.managers

import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.wallet.GroupLightningRollout
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthRollout

/**
 * Dedun's D4 (docs/giza-into-dedun.md): a keyed room that keeps a Lightning or a Wavelength
 * wallet changes no admins.
 *
 * Making someone an admin of a keyed room enrols them into its onchain FROST key, and taking it
 * away rotates their share out. Both group wallets are Iceberg keys, which have neither enrolment
 * nor refresh: after an admin change the wallet's signers would no longer be the room's admins,
 * and a demoted admin would go on co-signing it. Removing someone from the group is a different
 * question, and is not held by this.
 */
object WalletKeysHeld {

    enum class Wallet { LIGHTNING, WAVELENGTH }

    /** The wallet [chatRoomId] keeps that no admin change reaches, or null. */
    suspend fun of(database: DedunDatabase, chatRoomId: String): Wallet? = when {
        GroupLightningRollout.ENABLED &&
            GroupLightningWalletManager.walletFor(database, chatRoomId, GroupLightningRollout.NETWORK) != null -> Wallet.LIGHTNING
        GroupWavelengthRollout.NETWORKS.any { GroupWavelengthWalletManager.current(database, chatRoomId, it) != null } -> Wallet.WAVELENGTH
        else -> null
    }

    /**
     * Throws, naming the wallet, when [chatRoomId] keeps one. Called where every admin change
     * of a keyed room is made -- a promotion, a demotion, and a step-down another admin's device
     * proposes -- so that no path past the member row's menu gets round it.
     */
    suspend fun refuseAdminChange(database: DedunDatabase, chatRoomId: String) {
        when (of(database, chatRoomId)) {
            Wallet.LIGHTNING -> error(
                "This group has a Lightning wallet, whose key no change of admins can move. Open a new Lightning wallet for the new admins first."
            )
            Wallet.WAVELENGTH -> error(
                "This group has a Wavelength wallet, whose key no change of admins can move. Keep the admins as they are while it holds the group's money."
            )
            null -> Unit
        }
    }
}
