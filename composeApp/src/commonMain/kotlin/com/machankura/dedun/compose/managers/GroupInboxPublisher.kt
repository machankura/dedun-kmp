package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withTimeout
import com.machankura.dedun.compose.database.model.GroupInboxPublication
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.network.dto.RelayDTO
import com.machankura.dedun.compose.network.relays.EventPublishTransport
import com.machankura.dedun.compose.network.relays.RelayPool
import com.machankura.dedun.compose.network.sockets.NostrIncomingMessage
import com.machankura.dedun.compose.nostr.Relays
import com.machankura.dedun.compose.repository.GroupInboxRepository

/**
 * Sends what this device owes relays for its groups' inboxes (docs/group-inbox.md, D7 and D10):
 * the announcements it asked the group to sign, and the gift wraps around the replies it
 * proposed. Keeps trying until a relay takes each, backing off from a minute to half an hour, as
 * `ConfederationPublisher` does for its events and for the same reason: never through a
 * `NostrEvent` row.
 */
class GroupInboxPublisher(
    private val repository: GroupInboxRepository,
    private val transport: EventPublishTransport,
    private val firstRetry: Duration = 1.minutes,
    private val lastRetry: Duration = 30.minutes,
) {
    private val logger = Logger.withTag("GroupInboxPublisher")

    /** Runs until cancelled. */
    suspend fun run() {
        repository.observeUnpublished().collectLatest { owed ->
            var wait = firstRetry
            var remaining = owed
            while (remaining.isNotEmpty()) {
                remaining = remaining.filterNot { publish(it) }
                if (remaining.isEmpty()) break
                logger.w("${remaining.size} inbox event(s) not yet taken by any relay; trying again in $wait")
                delay(wait)
                wait = minOf(wait * 2, lastRetry)
            }
        }
    }

    /**
     * Sends [owed] to its relays. An announcement goes to the publish set too, so a stranger's
     * app finds it wherever it looks; a wrap goes only where its recipient reads, or the
     * publish set when they have said nowhere. Whether any relay accepted it.
     */
    suspend fun publish(owed: GroupInboxPublication): Boolean {
        val own = owed.relayList()
        val targets = when {
            owed.recipientPublicKey == null -> own + Relays.eventPublishRelaySet.map { it.url }
            own.isEmpty() -> Relays.eventPublishRelaySet.map { it.url }
            else -> own
        }.map { it.trim() }.filter { it.startsWith("wss://") || it.startsWith("ws://") }.distinct()

        val wire = wireEventOf(owed) ?: return false
        var accepted = false
        try {
            withTimeout((RelayPool.PUBLISH_TIMEOUT * 2).milliseconds) {
                transport.publishEvent(
                    nostrEvent = wire,
                    relays = targets.map { RelayDTO(url = it, read = false, write = true) }.toSet(),
                ).take(targets.size).collect { result ->
                    val ok = result.result as? NostrIncomingMessage.OkMessage
                    if (result.error == null && ok?.success == true) accepted = true
                }
            }
        } catch (e: TimeoutCancellationException) {
            // A relay that never answers. Whatever answered in time counts.
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.e("Could not send inbox event ${owed.eventId.take(8)}", e)
        }
        if (accepted) {
            repository.markPublished(owed.eventId, Clock.System.now())
            logger.i("Inbox event ${owed.eventId.take(8)} of kind ${owed.kind} is on a relay")
        }
        return accepted
    }

    private companion object {
        /** The event as the pool wants it. Never written to the `NostrEvent` table. */
        fun wireEventOf(owed: GroupInboxPublication): NostrEvent? = Event.fromJsonOrNull(owed.eventJson)?.let {
            NostrEvent(
                id = it.id,
                pubKey = it.pubKey,
                kind = it.kind,
                tags = it.tags,
                content = it.content,
                sig = it.sig,
                createdAt = kotlin.time.Instant.fromEpochSeconds(it.createdAt),
            )
        }
    }
}
