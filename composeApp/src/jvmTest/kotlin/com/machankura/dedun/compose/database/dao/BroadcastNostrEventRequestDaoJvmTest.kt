package com.machankura.dedun.compose.database.dao

import androidx.room3.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.BroadcastNostrEventRequest
import com.machankura.dedun.compose.database.model.NostrEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The outbound queue. Nothing else drains this table, so a row it fails to hand back is an
 * event that is never sent to any relay -- and the failure is silent, because a queue that
 * returns nothing looks exactly like a queue that is empty.
 *
 * That is not hypothetical here. The observer's predicate used to carry a `createdAt > :now`
 * bound whose `now` was evaluated once, when the Flow was built. Instants persist at second
 * resolution, so it hid every broadcast enqueued during the observer's own start second -- the
 * whole profile-creation burst -- along with everything a previous session had left pending.
 */
class BroadcastNostrEventRequestDaoJvmTest {

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )

    @AfterTest
    fun closeDb() = db.close()

    private val author = "a".repeat(64)

    private suspend fun queue(
        eventId: String,
        status: String = "pending",
        createdAt: Instant = Instant.fromEpochSeconds(1_000),
        relayURL: String = "wss://relay.example",
    ): Long {
        val id = eventId.padEnd(64, '0')
        // BroadcastNostrEventRequest.nostrEventId is a foreign key onto NostrEvent: a broadcast
        // cannot be queued for an event that was never stored.
        if (db.nostrEventDao().getNostrEventById(id) == null) {
            db.nostrEventDao().upsert(
                NostrEvent(
                    id = id,
                    pubKey = author,
                    kind = 1,
                    tags = emptyArray(),
                    content = "outbound",
                    sig = "0".repeat(128),
                )
            )
        }
        return db.broadcastNostrEventRequestDao().upsert(
            BroadcastNostrEventRequest(
                nostrEventId = id,
                relayURL = relayURL,
                status = status,
                createdAt = createdAt,
            )
        )
    }

    private suspend fun statusOf(requestId: Long): String =
        db.broadcastNostrEventRequestDao().getAllBroadcastNostrEventRequests()
            .single { it.id == requestId }.status

    private suspend fun pendingHead() =
        db.broadcastNostrEventRequestDao().observeBroadcastNostrEventRequestsByStatus("pending").first()

    /**
     * A request is flipped to "processing" before the publish is attempted, and a timeout or a
     * dropped socket leaves it there. Nothing observes "processing" or "failed", so without
     * this sweep those rows are dead weight and the events never go out.
     */
    @Test
    fun `an interrupted publish is requeued on startup`() = runBlocking {
        val processing = queue("1", status = "processing")
        val failed = queue("2", status = "failed")

        val changed = db.broadcastNostrEventRequestDao()
            .requeueStaleBroadcastNostrEventRequests(Instant.fromEpochSeconds(2_000))

        assertEquals(2, changed, "both stale rows should have been requeued")
        assertEquals("pending", statusOf(processing))
        assertEquals("pending", statusOf(failed))
    }

    /** A row already pending is not stale; touching it would inflate the reported count. */
    @Test
    fun `a pending request is left alone by the sweep`() = runBlocking {
        queue("1", status = "pending")

        val changed = db.broadcastNostrEventRequestDao()
            .requeueStaleBroadcastNostrEventRequests(Instant.fromEpochSeconds(2_000))

        assertEquals(0, changed)
    }

    /**
     * The reason the sweep is bounded at all. A publish running right now holds its row in
     * "processing"; requeueing that would hand the same event to a second publish while the
     * first is still in flight.
     */
    @Test
    fun `a request newer than the cutoff is left for the process that owns it`() = runBlocking {
        val inFlight = queue("1", status = "processing", createdAt = Instant.fromEpochSeconds(3_000))

        val changed = db.broadcastNostrEventRequestDao()
            .requeueStaleBroadcastNostrEventRequests(Instant.fromEpochSeconds(2_000))

        assertEquals(0, changed)
        assertEquals("processing", statusOf(inFlight))
    }

    /** The bound is `<=`, so a row stamped exactly on the cutoff second is swept. */
    @Test
    fun `a request stamped exactly on the cutoff is requeued`() = runBlocking {
        val onCutoff = queue("1", status = "processing", createdAt = Instant.fromEpochSeconds(2_000))

        val changed = db.broadcastNostrEventRequestDao()
            .requeueStaleBroadcastNostrEventRequests(Instant.fromEpochSeconds(2_000))

        assertEquals(1, changed)
        assertEquals("pending", statusOf(onCutoff))
    }

    /**
     * The regression the observer's comment describes. A row enqueued before the observer was
     * built -- a previous session's leftovers -- has to come back. The old bound compared
     * against a `now` captured when the Flow was created, so it returned nothing here and the
     * queue looked empty forever.
     */
    @Test
    fun `the queue hands back work enqueued before the observer existed`() = runBlocking {
        val old = queue("1", createdAt = Instant.fromEpochSeconds(1_000))

        val head = assertNotNull(pendingHead(), "a previous session's pending row was not returned")

        assertEquals(old, head.broadcastNostrEventRequest.id)
    }

    /** Oldest first: the queue drains in the order things were enqueued. */
    @Test
    fun `the queue hands back the oldest pending request first`() = runBlocking {
        queue("2", createdAt = Instant.fromEpochSeconds(3_000))
        val oldest = queue("1", createdAt = Instant.fromEpochSeconds(1_000))

        assertEquals(oldest, assertNotNull(pendingHead()).broadcastNostrEventRequest.id)
    }

    /**
     * Instants persist at second resolution, so a burst enqueued in one second is all one
     * timestamp, and the head of the queue is decided by the `id ASC` tiebreak.
     *
     * Note what this test does and does not do. It pins the observable drain order, which is
     * what a caller depends on. It cannot fail if the tiebreak is deleted: `id` is an
     * autoGenerate primary key and therefore the rowid, so sqlite's own unspecified ordering
     * already coincides with it under this plan -- removing `, id ASC` leaves every assertion
     * here passing. That coincidence is exactly why the explicit tiebreak is worth keeping:
     * it is not contractual, and an index or a different query plan can change it.
     */
    @Test
    fun `requests sharing a timestamp drain in insertion order`() = runBlocking {
        val sameSecond = Instant.fromEpochSeconds(1_000)
        val first = queue("1", createdAt = sameSecond)
        queue("2", createdAt = sameSecond)
        queue("3", createdAt = sameSecond)

        assertEquals(first, assertNotNull(pendingHead()).broadcastNostrEventRequest.id)
    }

    @Test
    fun `the queue is empty when nothing is pending`() = runBlocking {
        queue("1", status = "processing")

        assertNull(pendingHead(), "a processing row must not be handed out as pending work")
    }

    /**
     * One event is queued once per target relay, so this returns the oldest of several rows
     * rather than the only one.
     */
    @Test
    fun `the first request for an event is the oldest of its per-relay rows`() = runBlocking {
        val eventId = "1".padEnd(64, '0')
        val oldest = queue("1", createdAt = Instant.fromEpochSeconds(1_000), relayURL = "wss://one.example")
        queue("1", createdAt = Instant.fromEpochSeconds(2_000), relayURL = "wss://two.example")

        val found = assertNotNull(
            db.broadcastNostrEventRequestDao().getFirstBroadcastNostrEventRequestByNostrEventId(eventId)
        )

        assertEquals(oldest, found.id)
    }
}
