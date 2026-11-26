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
import com.machankura.dedun.compose.database.model.GroupDmOutbound
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.network.dto.RelayDTO
import com.machankura.dedun.compose.network.relays.EventPublishTransport
import com.machankura.dedun.compose.network.relays.RelayPool
import com.machankura.dedun.compose.network.sockets.NostrIncomingMessage
import com.machankura.dedun.compose.repository.GroupDmRepository

/**
 * Sends what a group's direct messages owe relays, and keeps trying until one takes each
 * (docs/group-threshold-ecdh.md, D8 and D10): the group's signed 10050 from the device that
 * proposed it, and the gift wraps of a message the group signed.
 *
 * Not through the `BroadcastNostrEventRequest` queue, for the confederation's reason: that
 * hangs off a `NostrEvent` row, and a group-signed event or a wrap is not one this app's
 * feed should hold. The owed rows are [GroupDmOutbound], marked published once a relay
 * accepts them, so a restart picks up where it left off.
 */
class GroupDmPublisher(
    private val repository: GroupDmRepository,
    private val transport: EventPublishTransport,
    private val firstRetry: Duration = 1.minutes,
    private val lastRetry: Duration = 30.minutes,
) {
    private val logger = Logger.withTag("GroupDmPublisher")

    /** Runs until cancelled. */
    suspend fun run() {
        repository.observeOwed().collectLatest { owed ->
            var wait = firstRetry
            var remaining = owed
            while (remaining.isNotEmpty()) {
                remaining = remaining.filterNot { publish(it) }
                if (remaining.isEmpty()) break
                logger.w("${remaining.size} direct message event(s) not yet taken by any relay; trying again in $wait")
                remaining.forEach { repository.markAttempt(it, Clock.System.now() + wait) }
                delay(wait)
                wait = minOf(wait * 2, lastRetry)
            }
        }
    }

    /** Sends [outbound] to every relay it names. Whether any accepted it. */
    suspend fun publish(outbound: GroupDmOutbound): Boolean {
        val event = outbound.wrapJson?.let { runCatching { Event.fromJson(it) }.getOrNull() } ?: return false
        val targets = outbound.relays.split(",").map { it.trim() }.filter { it.startsWith("wss://") }.distinct()
        if (targets.isEmpty()) return false
        var accepted = false
        try {
            withTimeout((RelayPool.PUBLISH_TIMEOUT * 2).milliseconds) {
                transport.publishEvent(
                    nostrEvent = NostrEvent(
                        id = event.id, pubKey = event.pubKey, kind = event.kind, tags = event.tags,
                        content = event.content, sig = event.sig,
                        createdAt = kotlin.time.Instant.fromEpochSeconds(event.createdAt),
                    ),
                    relays = targets.map { RelayDTO(url = it, read = false, write = true) }.toSet(),
                ).take(targets.size).collect { result ->
                    val ok = result.result as? NostrIncomingMessage.OkMessage
                    if (result.error == null && ok?.success == true) accepted = true
                }
            }
        } catch (_: TimeoutCancellationException) {
            // A relay that never answers. Whatever answered in time counts.
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.e("Could not send ${event.id.take(8)} of kind ${event.kind}", e)
        }
        if (accepted) {
            repository.markPublished(outbound, Clock.System.now())
            logger.i("${event.id.take(8)} of kind ${event.kind} is on a relay")
        }
        return accepted
    }
}
