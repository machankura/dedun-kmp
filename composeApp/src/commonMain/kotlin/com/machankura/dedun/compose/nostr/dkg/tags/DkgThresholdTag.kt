package com.machankura.dedun.compose.nostr.dkg.tags

import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

/**
 * The `t` of the t-of-n the ritual is generating a key for. Carried on the
 * proposal so every participant runs the ritual with the same `t` — ChillDKG
 * hashes the threshold and the participant set into the session identity, so
 * disagreement on `t` makes the session fail rather than silently produce a
 * weaker key.
 */
class DkgThresholdTag(
    val threshold: Int,
) {
    fun toTagArray() = assemble(threshold = threshold)

    companion object {
        const val TAG_NAME = "dkg_threshold"

        fun parse(tag: Array<String>): DkgThresholdTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }

            val threshold = tag[1].toIntOrNull() ?: return null

            return DkgThresholdTag(threshold = threshold)
        }

        fun assemble(threshold: Int): Array<String> = arrayOf(TAG_NAME, threshold.toString())

        fun assemble(dkgThresholdTag: DkgThresholdTag) = assemble(threshold = dkgThresholdTag.threshold)
    }
}
