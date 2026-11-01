package com.machankura.dedun.compose.database.dao

import androidx.room3.Room
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.MarmotGroupEvent
import com.machankura.dedun.compose.database.model.NostrEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The hand-written queries on [NostrEventDao], as SQLite actually runs them.
 *
 * Two of them carry a comment describing a bug that already shipped -- an expiry predicate
 * that kept exactly the expired rows and dropped the live ones, and a set of queries that
 * returned the oldest events under a limit instead of the newest. Both were reasoned about in
 * review and neither was caught by anything that runs, because a wrong `WHERE` clause is
 * still a valid query returning a plausible list. These pin the corrected behaviour so the
 * next edit to the predicate has to argue with a failing test.
 */
class NostrEventDaoJvmTest {

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )

    @AfterTest
    fun closeDb() = db.close()

    private val alice = "a".repeat(64)
    private val roomOne = "11".repeat(32)
    private val roomTwo = "22".repeat(32)

    private val epoch = Instant.fromEpochSeconds(0)
    private val distantFuture = Instant.fromEpochSeconds(4_000_000_000)

    private suspend fun storeEvent(
        id: String,
        kind: Int = MarmotGroupEvent.KIND,
        pubKey: String = alice,
        createdAt: Instant = Instant.fromEpochSeconds(1_000),
        tags: Array<Array<String>> = emptyArray(),
        content: String = "{}",
    ): NostrEvent = NostrEvent(
        id = id.padEnd(64, '0'),
        pubKey = pubKey,
        kind = kind,
        tags = tags,
        content = content,
        sig = "0".repeat(128),
        createdAt = createdAt,
    ).also { db.nostrEventDao().upsert(it) }

    /**
     * `MarmotGroupEvent.id` is a foreign key onto `NostrEvent.id`, so the parent row has to
     * exist first -- the indexed row is a projection of an event that was stored.
     */
    private suspend fun storeGroupEvent(
        id: String,
        chatRoomId: String = roomOne,
        createdAt: Instant = Instant.fromEpochSeconds(1_000),
        expiresAt: Instant? = null,
    ): MarmotGroupEvent {
        val event = storeEvent(id, createdAt = createdAt, tags = arrayOf(arrayOf("h", chatRoomId)))
        return MarmotGroupEvent(
            id = event.id,
            userPublicKey = alice,
            publicKey = alice,
            chatRoomId = chatRoomId,
            signature = "0".repeat(128),
            encryptedContent = "ciphertext",
            expiresAt = expiresAt,
            createdAt = createdAt,
        ).also { db.marmotGroupEventDao().upsert(it) }
    }

    private suspend fun groupEvents(
        rooms: Array<String> = arrayOf(roomOne),
        since: Instant = epoch,
        until: Instant = distantFuture,
        limit: Int = 100,
        now: Instant,
    ) = db.nostrEventDao().getMarmotGroupEvents(rooms, since, until, limit, now)

    /**
     * The regression this query's own comment describes. `expiresAt > :now` keeps what a
     * relay would still serve; the `expiresAt < :now` it replaced kept precisely the events a
     * relay had stopped serving and dropped every live one, so the set handed to negentropy
     * was the complement of the relay's for the whole group-chat sync path.
     */
    @Test
    fun `an expiring group event is served until it expires and not after`() = runBlocking {
        val now = Instant.fromEpochSeconds(2_000)
        val neverExpires = storeGroupEvent("1", expiresAt = null)
        val stillLive = storeGroupEvent("2", expiresAt = Instant.fromEpochSeconds(2_001))
        val expired = storeGroupEvent("3", expiresAt = Instant.fromEpochSeconds(1_999))
        val expiringNow = storeGroupEvent("4", expiresAt = now)

        val found = groupEvents(now = now).map { it.id }

        assertTrue(neverExpires.id in found, "an event with no expiry never stops being served")
        assertTrue(stillLive.id in found, "an event expiring in the future is still live")
        assertTrue(expired.id !in found, "an expired event must not be served")
        assertTrue(
            expiringNow.id !in found,
            "the bound is strict: an event expiring exactly now has stopped being served",
        )
    }

    /** NIP-01 bounds are inclusive, so an event stamped on either bound is inside the window. */
    @Test
    fun `the since and until bounds are inclusive`() = runBlocking {
        val now = Instant.fromEpochSeconds(10)
        val before = storeGroupEvent("1", createdAt = Instant.fromEpochSeconds(999))
        val onSince = storeGroupEvent("2", createdAt = Instant.fromEpochSeconds(1_000))
        val onUntil = storeGroupEvent("3", createdAt = Instant.fromEpochSeconds(2_000))
        val after = storeGroupEvent("4", createdAt = Instant.fromEpochSeconds(2_001))

        val found = groupEvents(
            since = Instant.fromEpochSeconds(1_000),
            until = Instant.fromEpochSeconds(2_000),
            now = now,
        ).map { it.id }

        assertEquals(setOf(onSince.id, onUntil.id), found.toSet())
        assertTrue(before.id !in found)
        assertTrue(after.id !in found)
    }

    @Test
    fun `only the requested rooms come back`() = runBlocking {
        val now = Instant.fromEpochSeconds(10)
        val mine = storeGroupEvent("1", chatRoomId = roomOne)
        val theirs = storeGroupEvent("2", chatRoomId = roomTwo)

        assertEquals(listOf(mine.id), groupEvents(rooms = arrayOf(roomOne), now = now).map { it.id })
        assertEquals(
            setOf(mine.id, theirs.id),
            groupEvents(rooms = arrayOf(roomOne, roomTwo), now = now).map { it.id }.toSet(),
        )
    }

    /** Newest first under a limit, which is the window a relay would hand back. */
    @Test
    fun `group events come back newest first and a limit keeps the newest`() = runBlocking {
        val now = Instant.fromEpochSeconds(10)
        val oldest = storeGroupEvent("1", createdAt = Instant.fromEpochSeconds(1_000))
        val middle = storeGroupEvent("2", createdAt = Instant.fromEpochSeconds(2_000))
        val newest = storeGroupEvent("3", createdAt = Instant.fromEpochSeconds(3_000))

        assertEquals(
            listOf(newest.id, middle.id, oldest.id),
            groupEvents(now = now).map { it.id },
        )
        assertEquals(listOf(newest.id, middle.id), groupEvents(limit = 2, now = now).map { it.id })
    }

    /**
     * Replay order. A commit stream has to be applied in the order it was sent, so this query
     * is the one place in the DAO that deliberately orders ascending.
     */
    @Test
    fun `a rooms group events replay oldest first`() = runBlocking {
        val third = storeEvent("3", createdAt = Instant.fromEpochSeconds(3_000), tags = arrayOf(arrayOf("h", roomOne)))
        val first = storeEvent("1", createdAt = Instant.fromEpochSeconds(1_000), tags = arrayOf(arrayOf("h", roomOne)))
        val second = storeEvent("2", createdAt = Instant.fromEpochSeconds(2_000), tags = arrayOf(arrayOf("h", roomOne)))

        val found = db.nostrEventDao().getMarmotGroupNostrEventsByChatRoomId(roomOne).map { it.id }

        assertEquals(listOf(first.id, second.id, third.id), found)
    }

    /**
     * The reason this query reads `NostrEvent` rather than joining `MarmotGroupEvent`: an
     * event whose indexing failed part way was stored without ever reaching that table, and
     * those are exactly the ones a replay exists to pick up. A join would skip precisely the
     * rows worth replaying.
     */
    @Test
    fun `an event that never reached the index is still replayed`() = runBlocking {
        val indexed = storeGroupEvent("1", createdAt = Instant.fromEpochSeconds(1_000))
        val neverIndexed = storeEvent(
            "2",
            createdAt = Instant.fromEpochSeconds(2_000),
            tags = arrayOf(arrayOf("h", roomOne)),
        )

        val found = db.nostrEventDao().getMarmotGroupNostrEventsByChatRoomId(roomOne).map { it.id }

        assertEquals(listOf(indexed.id, neverIndexed.id), found)
        assertTrue(
            db.nostrEventDao().getMarmotGroupEvents(arrayOf(roomOne), epoch, distantFuture, 100, epoch)
                .none { it.id == neverIndexed.id },
            "the un-indexed event is genuinely absent from the indexed table",
        )
    }

    @Test
    fun `a replay ignores events of other kinds`() = runBlocking {
        val groupEvent = storeEvent("1", tags = arrayOf(arrayOf("h", roomOne)))
        storeEvent("2", kind = 1, tags = arrayOf(arrayOf("h", roomOne)))

        val found = db.nostrEventDao().getMarmotGroupNostrEventsByChatRoomId(roomOne).map { it.id }

        assertEquals(listOf(groupEvent.id), found)
    }

    /**
     * Documented contract, not an accident: the `LIKE` is a prefilter over serialised tags, so
     * a room id sitting in some other tag position matches too. Callers must confirm the
     * event's own `h` tag before treating a row as this room's. Pinned so that anyone
     * tightening the query knows a caller may already depend on the loose behaviour, and
     * anyone loosening a caller knows why the check is there.
     */
    @Test
    fun `the replay prefilter can over-match and callers must confirm the h tag`() = runBlocking {
        val real = storeEvent("1", tags = arrayOf(arrayOf("h", roomOne)))
        val coincidence = storeEvent("2", tags = arrayOf(arrayOf("e", roomOne)))

        val found = db.nostrEventDao().getMarmotGroupNostrEventsByChatRoomId(roomOne).map { it.id }

        assertEquals(setOf(real.id, coincidence.id), found.toSet())
    }

    /**
     * `getNostrEventByPublicKeyAndKind` returns a single row from a query ordered newest
     * first, which is what makes it correct for replaceable events -- the latest metadata
     * event for an author, not whichever one SQLite happened to reach first.
     */
    @Test
    fun `the newest event wins for an author and kind`() = runBlocking {
        storeEvent("1", kind = 0, createdAt = Instant.fromEpochSeconds(1_000), content = "old")
        storeEvent("2", kind = 0, createdAt = Instant.fromEpochSeconds(2_000), content = "new")

        val found = db.nostrEventDao().getNostrEventByPublicKeyAndKind(alice, 0)

        assertNotNull(found)
        assertEquals("new", found.content)
    }

    /**
     * The two write paths differ and the difference matters: a nostr event is immutable under
     * its id, so `insert` with IGNORE is the right call when re-receiving an event from a
     * second relay, while `upsert` overwrites. Using the wrong one silently replaces a stored
     * event with a re-received copy that may carry different local columns.
     */
    @Test
    fun `insert keeps the stored event while upsert replaces it`() = runBlocking {
        val original = storeEvent("1", content = "original")

        db.nostrEventDao().insert(original.copy(content = "from another relay"))
        assertEquals(
            "original",
            db.nostrEventDao().getNostrEventById(original.id)?.content,
            "insert must ignore a conflicting id rather than overwrite",
        )

        db.nostrEventDao().upsert(original.copy(content = "replaced"))
        assertEquals("replaced", db.nostrEventDao().getNostrEventById(original.id)?.content)
    }

    /**
     * The paged reads use a strict `createdAt > :since`, which is what makes them safe to call
     * in a loop with the last row's timestamp as the next cursor. Worth pinning next to the
     * inclusive bound in `getMarmotGroupEvents`: the two are deliberately different, and a
     * reader who assumes one convention holds everywhere would introduce either a skipped row
     * or an endless loop.
     */
    @Test
    fun `the paged reads treat since as exclusive`() = runBlocking {
        val onBoundary = storeEvent("1", kind = 1, createdAt = Instant.fromEpochSeconds(1_000))
        val after = storeEvent("2", kind = 1, createdAt = Instant.fromEpochSeconds(1_001))

        val found = db.nostrEventDao()
            .getNostrEvents(arrayOf(1), Instant.fromEpochSeconds(1_000), 100)
            .map { it.id }

        assertEquals(listOf(after.id), found)
        assertTrue(onBoundary.id !in found, "`since` is exclusive on this query")
    }
}
