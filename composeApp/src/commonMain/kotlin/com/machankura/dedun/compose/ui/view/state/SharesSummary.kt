package com.machankura.dedun.compose.ui.view.state

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlin.time.Instant
import com.machankura.dedun.compose.database.model.types.ShareStanding

/**
 * A room's key, as the Shares section shows it (docs/frost-share-refresh.md,
 * Phase 11): the generation the group signs with, who holds what, what this
 * device is being asked, what is running, and what it may start.
 */
data class SharesSummary(
    val generation: Int,
    val members: List<Member>,
    val pending: List<PendingApproval>,
    /** Whether this device may propose a rotation, and if not, what it waits on. */
    val rotation: Rotation,
    /** This device's own id is stale and nothing is bringing it back yet. */
    val canAskToBeRepaired: Boolean,
    /** A rotation under way, from its one approval until the group signs the new generation. */
    val running: Running? = null,
    /** Why the newest rotation ended, when it ended without completing and nothing has run since. */
    val lastFailure: String? = null,
) {
    data class Member(val publicKey: HexKey, val name: String, val standing: ShareStanding, val isSelf: Boolean)

    companion object {
        /**
         * One row per member, from a roster with one per participant id.
         *
         * An admin removed and later reinstated holds two ids: the old one, retired
         * for good, and the new one bound at reinstatement. Both carry the same
         * nostr key, and the section is about people, so the row shows the id that
         * says what they hold now -- [ShareStanding.LIVE] over [ShareStanding.STALE]
         * over [ShareStanding.RETIRED]. A member keeps the place of their first id,
         * so a reinstated founder stays at the top.
         */
        fun byMember(perId: List<Member>): List<Member> =
            perId.groupBy { it.publicKey }.values.map { ids -> ids.minBy { it.standing.ordinal } }
    }

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

        /**
         * [SPEND_OPEN] and [STATEMENT_OPEN] are Dedun's: a wallet spend or sweep, or a payout,
         * transfer or attribution, still being signed.
         */
        enum class Reason { ROTATION_RUNNING, AWAITING_SIGNATURES, BEHIND, SPEND_OPEN, STATEMENT_OPEN }
    }

    /**
     * Something this device's owner is being asked, and the session it is for.
     * A rotation's carries who it leaves out, because agreeing to that is what
     * taking part is (docs/rotate-shares-single-approval.md, Phase 6).
     */
    data class PendingApproval(val sessionId: String, val kind: Kind, val withheld: List<Withheld> = emptyList()) {
        /** A rotation asks one thing, to take part; storing the new share stopped being a question. */
        enum class Kind { REFRESH_DEAL, REPAIR_HELP }
    }

    data class Withheld(val name: String, val standing: ShareStanding)

    /**
     * A rotation running past its one approval. With nothing left to approve,
     * this is the only place a member who took part sees it stall, and the only
     * way out of one.
     */
    data class Running(
        val sessionId: String,
        val step: Step,
        val waitingOn: List<String>,
        val since: Instant,
        /** This device is a recipient of a rotation not yet certified, so it may end it for everyone. */
        val canAbandon: Boolean,
    ) {
        enum class Step { DEALING, STORING, SIGNING }
    }
}
