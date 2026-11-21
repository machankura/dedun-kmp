package com.machankura.dedun.compose.network.relays

import kotlinx.coroutines.flow.Flow
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.network.dto.RelayDTO

/**
 * The slice of the relay layer a one-off publish needs.
 *
 * The same cut [LiveSubscriptionTransport] makes and for the same reason:
 * [RelaysSocketManager] starts collecting relay lists in its `init` and cannot be
 * stood up in a test, so a screen that hands it an event would otherwise have no
 * way to check what it does with the answers.
 *
 * Everything the app publishes as *itself* goes through the
 * `BroadcastNostrEventRequest` queue instead, which is durable and retried. This
 * exists for the one thing that must not: an event the group signed. The queue
 * hangs off a `NostrEvent` row, and a row for the group's event would put it into
 * the home feed and offer it to every relay the sync loop reconciles with -- see
 * `BroadcastGroupSignedEventViewModel` for the whole of that argument. The group
 * inbox's and direct messages' publishers send through it too (docs/group-inbox.md, D7).
 */
interface EventPublishTransport {

    /**
     * Sends [nostrEvent] to each of [relays], opening a socket to any it does not
     * already hold, and answers once per relay: the relay's OK, its rejection, or
     * the error that stopped it being asked.
     *
     * @see RelayPool.publishEvent
     */
    @Throws(com.machankura.dedun.compose.exceptions.NostrPublishException::class, kotlin.coroutines.cancellation.CancellationException::class)
    suspend fun publishEvent(nostrEvent: NostrEvent, relays: Set<RelayDTO>): Flow<NostrPublishResult>
}
