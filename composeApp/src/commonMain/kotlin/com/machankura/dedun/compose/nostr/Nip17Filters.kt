package com.machankura.dedun.compose.nostr

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip59Giftwrap.wraps.GiftWrapEvent
import com.machankura.dedun.compose.database.model.types.SynchronizationFilter

/**
 * The one filter shape that can return a NIP-17 message we are able to read.
 *
 * A gift wrap hides everything except who it is for. The author is the throwaway
 * key [GiftWrapEvent.create] mints and discards, the content is sealed to the
 * recipient, and `created_at` is randomised up to two days into the past. That
 * leaves the `p` tag as the only clause worth writing, and it has to name us:
 * naming a peer subscribes to mail no key of ours can open, and adding `authors`
 * matches nothing on any relay, ever. Both mistakes were live in three separate
 * call sites, so the filter is built in one place now and asserted in one place.
 */
object Nip17Filters {

    /**
     * Everything gift-wrapped to [publicKey], capped at [limit] events.
     *
     * Deliberately carries no `since`. NIP-59 back-dates a wrap by up to two days,
     * so a cursor built from the newest wrap we hold silently skips mail that was
     * sent later but stamped earlier.
     */
    fun inbox(
        publicKey: HexKey,
        limit: Int = DEFAULT_LIMIT,
    ) = SynchronizationFilter(
        kinds = arrayOf(GiftWrapEvent.KIND),
        tags = mapOf("p" to listOf(publicKey)),
        limit = limit,
    )

    /**
     * The same inbox, shaped for a REQ instead of for a negentropy request.
     *
     * Two types for one filter is not a duplication worth removing: a
     * [SynchronizationFilter] is what the request queue stores and what
     * `computeId` hashes, while a live subscription puts a quartz `Filter`
     * straight onto the wire. Keeping both here is what stops them drifting —
     * which for this filter means one of them quietly stops matching mail.
     *
     * The default limit is larger because this one is opened rather than queued:
     * NIP-01 scopes `limit` to the stored events a relay sends before EOSE and
     * explicitly not to the stream after it, so it bounds what a reconnect costs
     * without capping anything that arrives live.
     */
    fun liveInbox(
        publicKey: HexKey,
        limit: Int = DEFAULT_LIVE_LIMIT,
    ) = Filter(
        kinds = listOf(GiftWrapEvent.KIND),
        tags = mapOf("p" to listOf(publicKey)),
        limit = limit,
    )

    const val DEFAULT_LIMIT = 50

    const val DEFAULT_LIVE_LIMIT = 100
}
