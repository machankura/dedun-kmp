package com.machankura.dedun.compose.database.model.types

/**
 * How far a FROST signing session has got, from the point of view of the device
 * holding the row. Coordinator and signers move through the same ladder; the
 * coordinator simply has extra work to do at [COLLECTING_NONCES] and
 * [COLLECTING_PARTIAL_SIGNATURES].
 *
 * Declaration order is the ladder: `FrostSigningManager` compares ordinals to
 * keep the label moving forwards when messages arrive out of order, so the
 * collecting stages must stay in the order the session runs them.
 */
enum class FrostSigningStage {
    /** Proposal seen; waiting for enough signers to offer a nonce. */
    COLLECTING_NONCES,

    /** The signer set is fixed; waiting on their partial signatures. */
    COLLECTING_PARTIAL_SIGNATURES,

    /** Aggregated and verified. The event is signed. */
    COMPLETE,

    /** Abandoned. See `FrostSigningSession.failureReason`. */
    FAILED
}
