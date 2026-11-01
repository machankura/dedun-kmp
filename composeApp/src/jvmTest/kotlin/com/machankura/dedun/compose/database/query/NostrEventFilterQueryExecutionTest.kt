package com.machankura.dedun.compose.database.query

import androidx.room3.Room
import kotlinx.coroutines.runBlocking
import com.machankura.dedun.compose.database.DedunDatabase
import com.machankura.dedun.compose.database.builder.getRoomDatabase
import com.machankura.dedun.compose.database.model.NostrEvent
import com.machankura.dedun.compose.database.model.types.SynchronizationFilter
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * `NostrEventFilterQueryTest` pins the SQL string this builder produces. It cannot pin what
 * SQLite does with that string, and the gap between the two is where the expensive mistakes
 * live: SQL that is well-formed but rejected, a bound value whose index is off by one, a LIKE
 * whose escaping does not survive contact with the stored column, or a bound timestamp in
 * units the column was never written in. All four produce a query that looks right in a
 * string assertion.
 *
 * That last one is worth spelling out, because it spans two files that nothing forces to
 * agree. [NostrEventFilterQuery] binds `since`/`until` as `epochSeconds`;
 * `BennuConverters.instantToTimestamp` writes the `createdAt` column as `epochSeconds`. Change
 * either one to milliseconds and the filter silently selects nothing, or everything.
 *
 * The stakes are the same as for the builder test: negentropy reconciles our set against the
 * relay's set for the *same* filter, so any row this query gets wrong turns into an event
 * needlessly re-downloaded or needlessly pushed at a relay that filtered it out on purpose.
 */
class NostrEventFilterQueryExecutionTest {

    private val db: DedunDatabase = getRoomDatabase(
        Room.inMemoryDatabaseBuilder<DedunDatabase>()
    )

    @AfterTest
    fun closeDb() = db.close()

    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val carol = "c".repeat(64)

    private suspend fun store(
        id: String,
        pubKey: String = alice,
        kind: Int = 1,
        createdAt: Instant = Instant.fromEpochSeconds(1_000),
        content: String = "hello",
        tags: Array<Array<String>> = emptyArray(),
    ): NostrEvent = NostrEvent(
        id = id.padEnd(64, '0'),
        pubKey = pubKey,
        kind = kind,
        tags = tags,
        content = content,
        sig = "0".repeat(128),
        createdAt = createdAt,
    ).also { db.nostrEventDao().upsert(it) }

    private suspend fun matching(
        filter: SynchronizationFilter,
        limit: Int = NostrEventFilterQuery.NO_LIMIT,
    ): List<NostrEvent> = db.nostrEventDao().getNostrEventsMatchingFilter(
        NostrEventFilterQuery.build(filter, limit)
    )

    private fun List<NostrEvent>.ids(): List<String> = map { it.id }

    @Test
    fun `an ids filter selects exactly the listed events`() = runBlocking {
        val wanted = store("1")
        store("2")

        val found = matching(SynchronizationFilter(ids = arrayOf(wanted.id)))

        assertEquals(listOf(wanted.id), found.ids())
    }

    /**
     * The clause the builder emits for a present-but-empty list is the literal `0`. A string
     * assertion cannot say whether SQLite accepts that as a boolean expression; this can.
     */
    @Test
    fun `a present but empty list matches nothing rather than everything`() = runBlocking {
        store("1")
        store("2")

        assertTrue(matching(SynchronizationFilter(ids = emptyArray())).isEmpty())
        assertTrue(matching(SynchronizationFilter(authors = emptyArray())).isEmpty())
        assertTrue(matching(SynchronizationFilter(kinds = emptyArray())).isEmpty())
        assertTrue(matching(SynchronizationFilter(tags = mapOf("p" to emptyList()))).isEmpty())
    }

    @Test
    fun `no clauses at all selects every stored event`() = runBlocking {
        store("1")
        store("2")

        assertEquals(2, matching(SynchronizationFilter()).size)
    }

    /**
     * NIP-01 bounds are inclusive on both ends, and an event stamped exactly on the boundary
     * is the case that tells an inclusive bound from an exclusive one. The queries this
     * builder replaced used a strict `createdAt > :since`, which put boundary events in the
     * relay's set and not in ours.
     */
    @Test
    fun `since and until are inclusive on both ends`() = runBlocking {
        val before = store("1", createdAt = Instant.fromEpochSeconds(999))
        val onSince = store("2", createdAt = Instant.fromEpochSeconds(1_000))
        val between = store("3", createdAt = Instant.fromEpochSeconds(1_500))
        val onUntil = store("4", createdAt = Instant.fromEpochSeconds(2_000))
        val after = store("5", createdAt = Instant.fromEpochSeconds(2_001))

        val found = matching(
            SynchronizationFilter(
                since = Instant.fromEpochSeconds(1_000),
                until = Instant.fromEpochSeconds(2_000),
            )
        ).ids()

        assertTrue(onSince.id in found, "an event stamped exactly on `since` must be included")
        assertTrue(onUntil.id in found, "an event stamped exactly on `until` must be included")
        assertTrue(between.id in found)
        assertTrue(before.id !in found)
        assertTrue(after.id !in found)
    }

    /**
     * The cross-file invariant. The filter binds seconds; the converter writes seconds. If
     * either side moved to milliseconds this would keep compiling and start selecting the
     * wrong century.
     */
    @Test
    fun `the bound timestamps are in the same units the converter writes`() = runBlocking {
        val at = Instant.fromEpochSeconds(1_700_000_000)
        val event = store("1", createdAt = at)

        assertEquals(listOf(event.id), matching(SynchronizationFilter(since = at)).ids())
        assertEquals(listOf(event.id), matching(SynchronizationFilter(until = at)).ids())
        assertTrue(matching(SynchronizationFilter(since = at.plus(kotlin.time.Duration.parse("1s")))).isEmpty())
        assertTrue(matching(SynchronizationFilter(until = at.minus(kotlin.time.Duration.parse("1s")))).isEmpty())
    }

    @Test
    fun `a tag filter ORs the values of one name and ANDs across names`() = runBlocking {
        val both = store("1", tags = arrayOf(arrayOf("p", alice), arrayOf("e", "beef".padEnd(64, '0'))))
        val onlyP = store("2", tags = arrayOf(arrayOf("p", bob)))
        store("3", tags = arrayOf(arrayOf("e", "beef".padEnd(64, '0'))))

        val orOverValues = matching(
            SynchronizationFilter(tags = mapOf("p" to listOf(alice, bob)))
        ).ids()
        assertEquals(setOf(both.id, onlyP.id), orOverValues.toSet())

        val andOverNames = matching(
            SynchronizationFilter(
                tags = mapOf("p" to listOf(alice, bob), "e" to listOf("beef".padEnd(64, '0')))
            )
        ).ids()
        assertEquals(listOf(both.id), andOverNames)
    }

    /**
     * The reason [NostrEventFilterQuery] anchors on the tag name as well as the value. The
     * substring scan it replaced -- `tags LIKE '%<pubkey>%'` -- matched the same hex sitting
     * in any tag position, so a `p` filter pulled in every event that merely referenced that
     * key in an `e` tag.
     */
    @Test
    fun `a tag value is matched in its own position and not anywhere in the row`() = runBlocking {
        val taggedAsPerson = store("1", tags = arrayOf(arrayOf("p", carol)))
        store("2", tags = arrayOf(arrayOf("e", carol)))

        val found = matching(SynchronizationFilter(tags = mapOf("p" to listOf(carol)))).ids()

        assertEquals(listOf(taggedAsPerson.id), found, "the `e` tagged event is not a `p` match")
    }

    /**
     * Real tags carry a relay hint and a marker after the value, which is why the pattern
     * drops the closing bracket rather than matching the whole encoded tag.
     */
    @Test
    fun `a tag match tolerates trailing tag elements`() = runBlocking {
        val withHint = store(
            "1",
            tags = arrayOf(arrayOf("p", alice, "wss://relay.example", "mention")),
        )

        val found = matching(SynchronizationFilter(tags = mapOf("p" to listOf(alice)))).ids()

        assertEquals(listOf(withHint.id), found)
    }

    /** `escapeLike`: a wildcard inside a tag value must be a literal, not a widening match. */
    @Test
    fun `a wildcard inside a tag value does not widen the match`() = runBlocking {
        val literal = store("1", tags = arrayOf(arrayOf("d", "100%")))
        store("2", tags = arrayOf(arrayOf("d", "100 percent")))

        val found = matching(SynchronizationFilter(tags = mapOf("d" to listOf("100%")))).ids()

        assertEquals(listOf(literal.id), found, "the `%` was treated as a wildcard")
    }

    @Test
    fun `tagsAll requires every value of a name rather than any of them`() = runBlocking {
        val hasBoth = store("1", tags = arrayOf(arrayOf("p", alice), arrayOf("p", bob)))
        store("2", tags = arrayOf(arrayOf("p", alice)))

        val all = matching(SynchronizationFilter(tagsAll = mapOf("p" to listOf(alice, bob)))).ids()
        assertEquals(listOf(hasBoth.id), all)

        val any = matching(SynchronizationFilter(tags = mapOf("p" to listOf(alice, bob)))).ids()
        assertEquals(2, any.size, "the OR form should still match both")
    }

    /**
     * A relay hands back the newest events under a limit. The per-shape queries this replaced
     * ordered ascending under the same limit and so returned the oldest, which is the opposite
     * window.
     */
    @Test
    fun `results are newest first and a limit keeps the newest`() = runBlocking {
        val oldest = store("1", createdAt = Instant.fromEpochSeconds(1_000))
        val middle = store("2", createdAt = Instant.fromEpochSeconds(2_000))
        val newest = store("3", createdAt = Instant.fromEpochSeconds(3_000))

        assertEquals(listOf(newest.id, middle.id, oldest.id), matching(SynchronizationFilter()).ids())
        assertEquals(listOf(newest.id, middle.id), matching(SynchronizationFilter(), limit = 2).ids())
    }

    /** The `id DESC` tiebreak, without which a limit over equal timestamps is arbitrary. */
    @Test
    fun `events sharing a timestamp are ordered by id descending`() = runBlocking {
        val sameMoment = Instant.fromEpochSeconds(1_000)
        val lower = store("1", createdAt = sameMoment)
        val higher = store("2", createdAt = sameMoment)

        assertEquals(listOf(higher.id, lower.id), matching(SynchronizationFilter()).ids())
        assertEquals(listOf(higher.id), matching(SynchronizationFilter(), limit = 1).ids())
    }

    /**
     * The limit is bound *after* every tag pattern, so its placeholder is the last one in the
     * statement. Nothing but execution catches a binding index that drifted: the SQL string
     * would be identical either way.
     */
    @Test
    fun `binding order holds when a filter combines clauses with a limit`() = runBlocking {
        val wanted = store(
            "1",
            pubKey = alice,
            kind = 1,
            createdAt = Instant.fromEpochSeconds(1_500),
            content = "find me",
            tags = arrayOf(arrayOf("p", bob)),
        )
        store("2", pubKey = alice, kind = 1, createdAt = Instant.fromEpochSeconds(1_500), content = "find me", tags = arrayOf(arrayOf("p", carol)))
        store("3", pubKey = bob, kind = 1, createdAt = Instant.fromEpochSeconds(1_500), content = "find me", tags = arrayOf(arrayOf("p", bob)))

        val found = matching(
            SynchronizationFilter(
                authors = arrayOf(alice),
                kinds = arrayOf(1),
                since = Instant.fromEpochSeconds(1_000),
                until = Instant.fromEpochSeconds(2_000),
                search = "find me",
                tags = mapOf("p" to listOf(bob)),
            ),
            limit = 10,
        ).ids()

        assertEquals(listOf(wanted.id), found)
    }

    @Test
    fun `a search clause matches on content`() = runBlocking {
        val wanted = store("1", content = "the quick brown fox")
        store("2", content = "nothing to see")

        assertEquals(listOf(wanted.id), matching(SynchronizationFilter(search = "quick brown")).ids())
    }
}
