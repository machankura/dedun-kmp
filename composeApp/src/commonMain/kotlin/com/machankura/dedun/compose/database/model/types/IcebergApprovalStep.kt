package com.machankura.dedun.compose.database.model.types

/**
 * What an Iceberg ceremony asks a member before their device publishes anything of theirs.
 *
 * Only [JOIN] is asked now. The ceremony used to stop at all three, as the ChillDKG ceremony does, but
 * the checks behind [REVEAL] and [CONFIRM] are made by the device, not the person, so after joining it
 * takes them by itself. The two stay because rows and transcript lines written before name them.
 */
enum class IcebergApprovalStep {
    /**
     * Publish this device's commitments, which puts its owner in the ceremony. Joining then going
     * quiet holds the ceremony open for everybody, since every seed needs all of its holders.
     */
    JOIN,

    /**
     * Send this device's contributions to the seeds it holds, each encrypted to the other holders.
     * No longer asked: the device sends them once it has every commitment.
     */
    REVEAL,

    /**
     * Publish this device's public share and the hash of the transcript it saw. A real check -- two
     * members shown different commitments sign different transcripts, and the ceremony refuses to
     * finish on transcripts that disagree -- but the device's, so no longer asked.
     */
    CONFIRM,
}
