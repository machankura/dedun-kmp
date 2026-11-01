package com.machankura.dedun.compose.database.model.types

/**
 * How far a ChillDKG ritual has got, from the point of view of the device
 * holding the row. Coordinator and participants move through the same ladder;
 * the coordinator simply has extra work to do at [COLLECTING_HOST_KEYS],
 * [COLLECTING_ROUND_1] and [COLLECTING_ROUND_2].
 *
 * Declaration order is the ladder: `ChillDkgRitualManager` compares ordinals to
 * keep the label moving forwards when messages arrive out of order, so the
 * collecting stages must stay in the order the ritual runs them.
 */
enum class DkgRitualStage {
    /** Proposal seen; waiting on every member's host public key. */
    COLLECTING_HOST_KEYS,

    /** Params are settled; waiting on every member's round-1 message. */
    COLLECTING_ROUND_1,

    /** Coordinator's round-1 message is out; waiting on every member's round-2 signature. */
    COLLECTING_ROUND_2,

    /** Certificate issued and verified. The key exists. */
    COMPLETE,

    /** Abandoned. See `DkgSession.failureReason`. */
    FAILED
}
