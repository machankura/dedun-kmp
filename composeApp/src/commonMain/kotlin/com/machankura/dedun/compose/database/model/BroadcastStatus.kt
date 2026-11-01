package com.machankura.dedun.compose.database.model

import com.machankura.dedun.compose.exceptions.NostrPublishException

/**
 * What a `BroadcastNostrEventRequest.status` can be, and how a relay's answer
 * becomes one.
 *
 * Four of these were string literals scattered over the sync pump, the
 * navigation gate and the DAO's requeue; the fifth is new. [REJECTED] is a
 * relay's `["OK", id, false, reason]` with a reason that will not change on a
 * retry -- `blocked:`, `invalid:`, `pow:`, `restricted:` -- and is the one
 * terminal status the requeue on launch leaves alone: the same bytes sent to
 * the same relay get the same answer. A `rate-limited:` or `error:` refusal is
 * [FAILED] like a timeout, because those do change. A `duplicate:` refusal is
 * [PUBLISHED]: the relay is saying it already has the event, which is what
 * published means, and it is the answer a byte-identical retry of a commit
 * gets from a relay that took the first copy.
 *
 * The distinction matters most to publish-before-apply: a commit's obligation
 * is discarded only when every relay in its scope has [REJECTED] it, and stays
 * pending through any number of [FAILED]s. See docs/marmot-v2.md, Phase 6.
 */
object BroadcastStatus {
    const val PENDING = "pending"
    const val PROCESSING = "processing"
    const val PUBLISHED = "published"
    const val FAILED = "failed"
    const val REJECTED = "rejected"

    /** NIP-01 `OK` prefixes a relay will keep answering with. */
    private val TERMINAL_PREFIXES = listOf("blocked:", "invalid:", "pow:", "restricted:")

    /** The status a publish attempt ends in, from what it threw or was answered. */
    fun of(error: Throwable?): String = when {
        error == null -> PUBLISHED
        error !is NostrPublishException -> FAILED
        else -> {
            val reason = error.message.orEmpty().trim()
            when {
                reason.startsWith("duplicate:") -> PUBLISHED
                TERMINAL_PREFIXES.any { reason.startsWith(it) } -> REJECTED
                else -> FAILED
            }
        }
    }
}
