package com.machankura.dedun.compose.database.model.types

/**
 * One thing a ChillDKG ritual needs this device's owner to agree to before it
 * will publish on their behalf.
 *
 * The ritual is driven by arriving messages, so without these it runs to
 * completion on its own — a relay delivering an event to a phone in a pocket is
 * enough to enrol its owner in a group's permanent signing quorum. Each step is a
 * separate decision because each publishes something different and commits the
 * member to something different, and each is presented on its own screen.
 *
 * The coordinator's two aggregations are deliberately not here. They relay other
 * members' already-published messages and disclose nothing of the coordinator's
 * own, so gating them would stall the whole group on one person's attention
 * without protecting anybody.
 */
enum class DkgApprovalStep {
    /**
     * Publish this device's host public key, which is what puts its owner in the
     * participant set. The most consequential of the three: `n` is fixed from here,
     * and every later step assumes this member is in.
     */
    HOST_KEY,

    /**
     * Publish this device's contribution to the key. Every member's is mixed in, so
     * this is the point at which the member's own secret material starts shaping a
     * key they will be expected to help sign with.
     */
    ROUND_1,

    /**
     * Sign to confirm the coordinator's combined result matches what this device
     * sent. Refusing here is what stops a coordinator substituting a different key
     * than the members contributed to, so it is a check, not a formality.
     */
    ROUND_2
}
