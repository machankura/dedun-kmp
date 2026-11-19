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
    /** This device holds a live share and no rotation is running: it may propose one. */
    val canRotate: Boolean,
    /** This device's own id is stale and nothing is bringing it back yet. */
    val canAskToBeRepaired: Boolean,
) {
    data class Member(val publicKey: HexKey, val name: String, val standing: ShareStanding, val isSelf: Boolean)

    /** Something this device's owner is being asked, and the session it is for. */
    data class PendingApproval(val sessionId: String, val kind: Kind) {
        enum class Kind { REFRESH_DEAL, REFRESH_CONFIRM, REPAIR_HELP }
    }
}
