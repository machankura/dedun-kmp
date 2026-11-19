package com.machankura.dedun.compose.database.model.types

/**
 * How far a share refresh has got on this device (docs/frost-share-refresh.md,
 * Phase 7). Declaration order is the ladder.
 */
enum class ShareRefreshStage {
    /** Proposed; waiting on every recipient's round-1 message, or on this device's owner to deal. */
    COLLECTING_ROUND_1,

    /** The coordinator's sum is in; waiting on every recipient's confirmation, or on this device's owner to confirm. */
    COLLECTING_ROUND_2,

    /** The certificate verified: the new generation is the one the group signs with. */
    COMPLETE,

    /** Abandoned. See `ShareRefreshSession.failureReason`. */
    FAILED,
}
