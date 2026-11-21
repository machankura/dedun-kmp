package com.machankura.dedun.compose.exceptions

/**
 * The room's changes need the group's signature, and this build cannot ask for one
 * yet: a bare commit from here would be applied by Dedun's devices and dropped by
 * Giza's, and the room would fork. See `GovernanceMarker` and docs/giza-into-dedun.md,
 * Phase 1.
 */
class MarmotGovernedRoomException(
    message: String? = "Changes to this group need the group's signature, which this version of Dedun cannot ask for yet",
    cause: Throwable? = null,
) : Exception(message, cause)
