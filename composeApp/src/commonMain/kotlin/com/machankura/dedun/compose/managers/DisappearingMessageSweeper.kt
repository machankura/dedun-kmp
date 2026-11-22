package com.machankura.dedun.compose.managers

import co.touchlab.kermit.Logger
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.model.GiftWrapPayload
import com.machankura.dedun.compose.nostr.DisappearingMessages

/**
 * Whether an expired payload may be deleted yet. What a person wrote may go the moment it expires;
 * a ceremony's round has to wait until its session has finished with it, because the session is
 * rebuilt from those rows. docs/disappearing-messages.md, D6 and Phase 5.
 */
fun interface PayloadRetention {
    suspend fun isReleased(database: DedunDatabase, payload: GiftWrapPayload): Boolean
}

/**
 * Deletes, from this device, what has disappeared: expired NIP-17 payloads with their wraps, seals
 * and chat lines; wraps addressed to somebody else once the relay would have dropped them; and
 * tombstones that have outlived their wraps. docs/disappearing-messages.md, D6 and Phase 4.
 */
object DisappearingMessageSweeper {
    private val logger = Logger.withTag("DisappearingMessageSweeper")

    /** How often the sweeper looks again when nothing is due sooner: a released ceremony, an unopened wrap. */
    val PERIOD: Duration = 1.hours

    data class Swept(
        val payloads: Int = 0,
        val wraps: Int = 0,
        val tombstones: Int = 0,
        /** The chat lines that went, so a notification showing one can be withdrawn. */
        val messageIds: Set<Long> = emptySet(),
        /** Messages this device wrote that expired before they could be sent. */
        val unsent: Int = 0,
    ) {
        val isEmpty: Boolean get() = payloads == 0 && wraps == 0 && tombstones == 0
    }

    suspend fun sweep(
        database: DedunDatabase,
        retention: PayloadRetention,
        now: Instant = Clock.System.now(),
    ): Swept {
        val dao = database.disappearingDao()
        var payloads = 0
        var unsent = 0
        val messageIds = mutableSetOf<Long>()
        dao.duePayloads(now).forEach { payload ->
            if (!retention.isReleased(database, payload)) return@forEach
            messageIds += dao.sweepPayload(payload)
            payloads += 1
            // Never sealed, so never sent: sealGiftWrapPayload refused it as too late.
            if (payload.giftWrapSealId == null && DisappearingMessages.classOf(payload.kind) == DisappearingMessages.ExpiryClass.Written) unsent += 1
        }
        val groupMessages = dao.sweepGroupMessages(now)
        val swept = Swept(
            payloads = payloads + groupMessages,
            wraps = dao.deleteDueUnlinkedWraps(now),
            tombstones = dao.pruneTombstones(now),
            messageIds = messageIds,
            unsent = unsent,
        )
        if (!swept.isEmpty) logger.i { "Swept ${swept.payloads} payload(s), ${swept.wraps} wrap(s), ${swept.tombstones} tombstone(s)" }
        return swept
    }

    /**
     * Sweeps whenever something falls due, and every [PERIOD] besides, until cancelled. The soonest
     * expiry is observed, so a message stamped to go in five minutes is not left for an hour; a
     * sweep that deletes something changes that table and starts the wait again.
     */
    suspend fun run(database: DedunDatabase, retention: PayloadRetention, onSwept: suspend (Swept) -> Unit) {
        database.disappearingDao().observeNextPayloadExpiry().collectLatest { next ->
            next?.let { due ->
                val wait = due - Clock.System.now()
                if (wait > Duration.ZERO) delay(wait)
            }
            while (true) {
                runCatching { sweep(database, retention) }
                    .onSuccess { if (!it.isEmpty) onSwept(it) }
                    .onFailure { logger.e("Sweep failed", it) }
                delay(PERIOD)
            }
        }
    }

    /**
     * A wrap that arrived carrying a message already past its expiry: never filed, and remembered so
     * a re-sync does not open it again before the relay drops it (D6). The wrap's own expiry is
     * when that happens; without one, two days past the message's, which is the most the wrap can
     * outlive it by (D5).
     */
    suspend fun dropOnArrival(database: DedunDatabase, wrapId: String, wrapTags: Array<Array<String>>, wrapCreatedAt: Long, rumorExpiry: Instant) {
        val dropAfter = DisappearingMessages.expirationOf(wrapTags, wrapCreatedAt)?.let { Instant.fromEpochSeconds(it) }
            ?: (rumorExpiry + DisappearingMessages.WRAP_SLACK.seconds)
        database.disappearingDao().dropOnArrival(wrapId, dropAfter, tombstoneUntil = dropAfter + DisappearingMessages.WRAP_SLACK.seconds)
        logger.i { "Wrap $wrapId carried a message that expired at $rumorExpiry; not filed" }
    }
}

/**
 * What a person wrote goes the moment it expires; nothing else goes yet. The retention Phase 4
 * ships with, so that the sweeper deletes nothing a ceremony still reads before Phase 5 says when
 * each protocol is done with its rounds (docs/disappearing-messages.md, Phase 4).
 */
object WrittenOnlyRetention : PayloadRetention {
    override suspend fun isReleased(database: DedunDatabase, payload: GiftWrapPayload): Boolean =
        DisappearingMessages.classOf(payload.kind) == DisappearingMessages.ExpiryClass.Written
}
