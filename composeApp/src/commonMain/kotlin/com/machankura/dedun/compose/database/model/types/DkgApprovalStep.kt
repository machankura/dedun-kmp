package com.machankura.dedun.compose.database.model.types

/**
 * Everything a ChillDKG ritual has ever asked this device's owner before publishing
 * on their behalf. It asks one of them now.
 *
 * The ritual is driven by arriving messages, so without a gate it runs to
 * completion on its own -- a relay delivering an event to a phone in a pocket would
 * be enough to enrol its owner in a group's permanent signing quorum. [HOST_KEY] is
 * that gate, and the only one: joining is a decision nothing on the device can make
 * for its owner. What follows it is not. Contributing follows from having joined,
 * and confirming is a check the device makes itself, so neither is asked about
 * (docs/automatic-key-ceremony.md).
 *
 * [ROUND_1] and [ROUND_2] stay because they were asked about until then, and
 * `DkgSession.approvalRequestedThrough` stores a step by name: a row written by an
 * older build has to keep decoding, and its request lines keep rendering.
 *
 * The coordinator's two aggregations were never here. They relay other members'
 * already-published messages and disclose nothing of the coordinator's own, so
 * gating them would stall the whole group on one person's attention without
 * protecting anybody.
 */
enum class DkgApprovalStep {
    /**
     * Publish this device's host public key, which is what puts its owner in the
     * participant set -- and, since nothing else is asked, agreeing to the whole
     * ceremony. `n` is fixed from here, and every later step assumes this member is
     * in.
     */
    HOST_KEY,

    /**
     * Publish this device's contribution to the key. Asked about by older builds;
     * now taken automatically once every host key is in.
     */
    ROUND_1,

    /**
     * Sign to confirm the coordinator's combined result matches what this device
     * sent. Asked about by older builds; now taken automatically, since the device
     * is what makes the check -- a mismatch fails the ceremony with nobody looking.
     */
    ROUND_2
}
