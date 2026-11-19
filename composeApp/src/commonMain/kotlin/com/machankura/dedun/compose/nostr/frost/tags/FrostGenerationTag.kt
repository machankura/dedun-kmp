package com.machankura.dedun.compose.nostr.frost.tags

import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

/**
 * Which generation of the key's shares a signing session signs with
 * (docs/frost-share-refresh.md).
 *
 * Rides beside [FrostKeyTag], and only from generation 1: a session at the
 * ceremony's own generation carries no tag, so it is byte for byte what it was
 * before shares could change, and an older build reads it exactly as it always
 * has. An absent tag means 0.
 *
 * A share of one generation and the public share of another do not match, so a
 * partial signature made across them cannot aggregate. A device that holds no
 * share of the generation named here declines the session, out loud.
 */
class FrostGenerationTag(
    val generation: Int,
) {
    companion object {
        const val TAG_NAME = "frost_generation"

        fun parse(tag: Array<String>): FrostGenerationTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }
            val generation = tag[1].toIntOrNull() ?: return null
            ensure(generation >= 0) { return null }

            return FrostGenerationTag(generation)
        }

        fun assemble(generation: Int): Array<String> = arrayOf(TAG_NAME, generation.toString())
    }
}
