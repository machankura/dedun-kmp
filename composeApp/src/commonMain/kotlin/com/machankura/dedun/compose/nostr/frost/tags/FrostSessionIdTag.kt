package com.machankura.dedun.compose.nostr.frost.tags

import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

/**
 * Binds a signing message to one session.
 *
 * A group signs more than once with the same key, and an abandoned attempt at
 * signing one event must never have its messages fed into a live attempt at
 * another. That is stricter here than it is for a DKG: every signer's secret
 * nonce is derived per session, so two sessions sharing an id would be two
 * different messages signed under one nonce -- which is how a secret share
 * leaks.
 */
class FrostSessionIdTag(
    val sessionId: String,
) {
    fun toTagArray() = assemble(sessionId = sessionId)

    companion object {
        const val TAG_NAME = "frost_session"

        fun parse(tag: Array<String>): FrostSessionIdTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }

            return FrostSessionIdTag(sessionId = tag[1])
        }

        fun assemble(sessionId: String): Array<String> = arrayOf(TAG_NAME, sessionId)

        fun assemble(frostSessionIdTag: FrostSessionIdTag) =
            assemble(sessionId = frostSessionIdTag.sessionId)
    }
}
