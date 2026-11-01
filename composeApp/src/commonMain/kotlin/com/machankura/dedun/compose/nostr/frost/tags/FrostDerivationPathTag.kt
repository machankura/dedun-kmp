package com.machankura.dedun.compose.nostr.frost.tags

import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure
import com.machankura.dedun.compose.managers.SharedKeyDerivation

/**
 * The path the room's id was derived at, `m/9420/0/0` style.
 *
 * Recorded rather than assumed. `SharedKeyDerivation.MARMOT_ADMIN_GROUP_PATH`
 * is the only path anything walks today, but a room derived at a second one
 * would be unfindable by a lookup that hardcodes the first, and the path is
 * also what rebuilds the `TweakCache` a signing session needs.
 *
 * Hardened indices are rejected on parse: hardened derivation needs the parent
 * private key, which in a threshold group nobody has, so a path carrying one
 * was never walked.
 */
class FrostDerivationPathTag(
    val path: List<Long>,
) {
    fun toTagArray() = assemble(path = path)

    companion object {
        const val TAG_NAME = "frost_path"

        fun parse(tag: Array<String>): FrostDerivationPathTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }

            val path = SharedKeyDerivation.parsePathString(tag[1]) ?: return null

            return FrostDerivationPathTag(path = path)
        }

        fun assemble(path: List<Long>): Array<String> =
            arrayOf(TAG_NAME, SharedKeyDerivation.formatPath(path))

        fun assemble(frostDerivationPathTag: FrostDerivationPathTag) =
            assemble(path = frostDerivationPathTag.path)
    }
}
