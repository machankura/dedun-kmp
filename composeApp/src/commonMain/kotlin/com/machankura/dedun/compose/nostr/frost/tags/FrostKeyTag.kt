package com.machankura.dedun.compose.nostr.frost.tags

import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

/**
 * Which key the group is being asked to sign with, named by the ceremony that
 * created it.
 *
 * A group can hold more than one shared key -- a ceremony is re-runnable, and
 * a member leaving is a reason to run another -- and a signer holds a
 * different secret share under each. Signing with the share from the wrong
 * ceremony produces a partial signature that cannot aggregate, so the session
 * says which one from the start rather than leaving each device to guess at
 * its most recent.
 */
class FrostKeyTag(
    val dkgSessionId: String,
) {
    fun toTagArray() = assemble(dkgSessionId = dkgSessionId)

    companion object {
        const val TAG_NAME = "frost_key"

        fun parse(tag: Array<String>): FrostKeyTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }

            return FrostKeyTag(dkgSessionId = tag[1])
        }

        fun assemble(dkgSessionId: String): Array<String> = arrayOf(TAG_NAME, dkgSessionId)

        fun assemble(frostKeyTag: FrostKeyTag) = assemble(dkgSessionId = frostKeyTag.dkgSessionId)
    }
}
