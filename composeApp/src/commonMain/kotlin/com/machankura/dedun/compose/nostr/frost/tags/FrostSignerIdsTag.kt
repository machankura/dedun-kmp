package com.machankura.dedun.compose.nostr.frost.tags

import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

/**
 * The participants the coordinator chose to sign with, as their FROST ids, in
 * the order their nonces were aggregated.
 *
 * A t-of-n key does not need everyone, so somebody has to pick which t, and
 * only the coordinator sees every nonce. Every signer then has to build the
 * same session from the same set in the same order -- FROST binds the set into
 * the challenge, so a device that disagrees about who is signing produces a
 * partial signature that will not aggregate.
 */
class FrostSignerIdsTag(
    val signerIds: List<Int>,
) {
    fun toTagArray() = assemble(signerIds = signerIds)

    companion object {
        const val TAG_NAME = "frost_signers"

        fun parse(tag: Array<String>): FrostSignerIdsTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }

            // Order is meaningful, so a single unparseable entry invalidates the
            // whole list rather than quietly shortening it.
            val ids = tag[1].split(",").map { it.trim().toIntOrNull() ?: return null }
            if (ids.isEmpty()) return null

            return FrostSignerIdsTag(signerIds = ids)
        }

        fun assemble(signerIds: List<Int>): Array<String> =
            arrayOf(TAG_NAME, signerIds.joinToString(","))

        fun assemble(frostSignerIdsTag: FrostSignerIdsTag) =
            assemble(signerIds = frostSignerIdsTag.signerIds)
    }
}
