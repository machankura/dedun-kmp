package com.machankura.dedun.compose.nostr.dkg.tags

import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

/**
 * Binds a DKG message to one ritual. A group can run a ChillDKG session more
 * than once — a member leaves, a threshold changes — and messages from an
 * abandoned attempt must never be fed into a live one.
 */
class DkgSessionIdTag(
    val sessionId: String,
) {
    fun toTagArray() = assemble(sessionId = sessionId)

    companion object {
        const val TAG_NAME = "dkg_session"

        fun parse(tag: Array<String>): DkgSessionIdTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }

            return DkgSessionIdTag(sessionId = tag[1])
        }

        fun assemble(sessionId: String): Array<String> = arrayOf(TAG_NAME, sessionId)

        fun assemble(dkgSessionIdTag: DkgSessionIdTag) = assemble(sessionId = dkgSessionIdTag.sessionId)
    }
}
