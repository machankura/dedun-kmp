package com.machankura.dedun.compose.ui.view.state

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.machankura.dedun.compose.database.model.types.ShareStanding

/**
 * A room's key, as the Shares section shows it (docs/frost-share-refresh.md,
 * Phase 11): the generation the group signs with, who holds what, what this
 * device is being asked, and what it may start.
 */
data class SharesSummary(
    val generation: Int,
    val members: List<Member>,
    val pending: List<PendingApproval>,
    /** Whether this device may propose a rotation, and if not, what it waits on. */
    val rotation: Rotation,
    /** This device's own id is stale and nothing is bringing it back yet. */
    val canAskToBeRepaired: Boolean,
) {
    data class Member(val publicKey: HexKey, val name: String, val standing: ShareStanding, val isSelf: Boolean)

    /**
     * The rotate action, as the section shows it (docs/rotation-waits-for-key-state.md).
     * [ShareRefreshManager.rotationHold][com.machankura.dedun.compose.managers.ShareRefreshManager.rotationHold]
     * decides it, the same function proposing asks, so the button and the protocol agree.
     */
    sealed interface Rotation {
        /** No live share here, or a build without the feature: no button. */
        data object Unavailable : Rotation

        data object Available : Rotation

        /**
         * The button, disabled, and why. [canAskForSignature]: the group owes the
         * key state at this generation and nobody here is asking for it, so this
         * device can.
         */
        data class Held(val reason: Reason, val canAskForSignature: Boolean = false) : Rotation

        enum class Reason { ROTATION_RUNNING, AWAITING_SIGNATURES, BEHIND }
    }

    /** Something this device's owner is being asked, and the session it is for. */
    data class PendingApproval(val sessionId: String, val kind: Kind) {
        enum class Kind { REFRESH_DEAL, REFRESH_CONFIRM, REPAIR_HELP }
    }
}
