package com.machankura.dedun.compose.database.model.types

/**
 * How far a share repair -- re-enrollment of a member a refresh left stale --
 * has got on this device (docs/frost-share-refresh.md, Phase 10).
 */
enum class ShareRepairStage {
    /** The id asking is not yet bound to the identity asking: the group has to sign that first (10b). */
    AWAITING_BINDING,

    /** Helpers exchanging their round-1.1 shares and sending the target their sums. */
    COLLECTING,

    /** The target holds its share and has proved it; every device marks the id live. */
    COMPLETE,

    /** Abandoned. See `ShareRepairSession.failureReason`. */
    FAILED,
}
