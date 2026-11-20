package com.machankura.dedun.compose.ui.view.state

import com.vitorpamplona.quartz.nip01Core.core.HexKey

/**
 * Where a keyed room stands on needing the group's signature for its changes.
 * docs/member-admin-promotion.md, R2 and Phase 4b.
 */
sealed interface GovernanceStatus {
    /** It does: every change is proposed, signed by the group, and only then applied. */
    data class Governed(val minimumAdmins: Int) : GovernanceStatus

    /**
     * It does not yet, and every member's device could follow: [switcher]'s device
     * switches it on by itself, the next time it is online. Null [switcher] when no
     * admin holds a leaf. docs/remove-convenient-groups.md, "After the build".
     */
    data class Ready(val minimumAdmins: Int, val switcher: HexKey?) : GovernanceStatus

    /** It does not yet, and these members have not said their device could follow. */
    data class Waiting(val missing: Set<HexKey>) : GovernanceStatus
}
