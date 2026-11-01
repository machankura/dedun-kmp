package com.machankura.dedun.compose.database.query

import androidx.sqlite.SQLiteStatement
import com.machankura.dedun.compose.database.model.types.SynchronizationFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Pins the SQL a [SynchronizationFilter] turns into.
 *
 * Negentropy is only as good as the agreement between the set we build from this query and the
 * set the relay builds from the same filter sent in NEG-OPEN. A clause that is dropped or a
 * bound that is off by one shows up not as an error but as reconciliation reporting differences
 * that are not real — events re-downloaded, or events pushed at a relay that filtered them out
 * on purpose. So the translation is asserted rather than eyeballed.
 */
class NostrEventFilterQueryTest {

    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val note = "c".repeat(64)

    @Test
    fun `applies every clause a nostr filter can carry`() {
        val query = NostrEventFilterQuery.build(
            SynchronizationFilter(
                ids = arrayOf(note),
                authors = arrayOf(alice, bob),
                kinds = arrayOf(1, 6),
                since = Instant.fromEpochSeconds(1_000),
                until = Instant.fromEpochSeconds(2_000),
            )
        )

        assertEquals(
            "SELECT * FROM NostrEvent WHERE id IN (?) AND pubKey IN (?, ?) AND kind IN (?, ?) " +
                "AND createdAt >= ? AND createdAt <= ? ORDER BY createdAt DESC, id DESC",
            query.sql,
        )
        assertEquals(listOf(note, alice, bob, 1L, 6L, 1_000L, 2_000L), query.boundValues())
    }

    /**
     * `until` is the clause the per-shape queries this replaced could not express at all: it was
     * sent to the relay and never applied locally, so every local event past the window came
     * back as one the relay was "missing".
     */
    @Test
    fun `time bounds are inclusive on both ends`() {
        val query = NostrEventFilterQuery.build(
            SynchronizationFilter(since = Instant.fromEpochSeconds(7), until = Instant.fromEpochSeconds(9))
        )

        assertEquals(
            "SELECT * FROM NostrEvent WHERE createdAt >= ? AND createdAt <= ? ORDER BY createdAt DESC, id DESC",
            query.sql,
        )
        assertEquals(listOf(7L, 9L), query.boundValues())
    }

    /** AND between tag names, OR between the values of one name — as `FilterMatcher` does. */
    @Test
    fun `tags are matched by name and value Not by substring`() {
        val query = NostrEventFilterQuery.build(
            SynchronizationFilter(
                kinds = arrayOf(1059),
                tags = mapOf("p" to listOf(alice, bob), "e" to listOf(note)),
            )
        )

        assertEquals(
            "SELECT * FROM NostrEvent WHERE kind IN (?) " +
                "AND (tags LIKE ? ESCAPE '\\' OR tags LIKE ? ESCAPE '\\') " +
                "AND (tags LIKE ? ESCAPE '\\') ORDER BY createdAt DESC, id DESC",
            query.sql,
        )
        assertEquals(
            listOf(1059L, "%[\"p\",\"$alice\"%", "%[\"p\",\"$bob\"%", "%[\"e\",\"$note\"%"),
            query.boundValues(),
        )
    }

    /** tagsAll is AND between values too, so each value gets its own clause. */
    @Test
    fun `tagsAll requires every value`() {
        val query = NostrEventFilterQuery.build(
            SynchronizationFilter(tagsAll = mapOf("t" to listOf("nostr", "kmp")))
        )

        assertEquals(
            "SELECT * FROM NostrEvent WHERE tags LIKE ? ESCAPE '\\' AND tags LIKE ? ESCAPE '\\' " +
                "ORDER BY createdAt DESC, id DESC",
            query.sql,
        )
        assertEquals(listOf("%[\"t\",\"nostr\"%", "%[\"t\",\"kmp\"%"), query.boundValues())
    }

    /** A wildcard in a tag value must not widen the match. */
    @Test
    fun `like wildcards inside a tag value are escaped`() {
        val query = NostrEventFilterQuery.build(
            SynchronizationFilter(tags = mapOf("d" to listOf("100%_off")))
        )

        assertEquals(listOf("%[\"d\",\"100\\%\\_off\"%"), query.boundValues())
    }

    /** A present-but-empty list matches nothing, the way a relay reads `{"kinds":[]}`. */
    @Test
    fun `an empty list matches nothing`() {
        assertEquals(
            "SELECT * FROM NostrEvent WHERE 0 ORDER BY createdAt DESC, id DESC",
            NostrEventFilterQuery.build(SynchronizationFilter(kinds = emptyArray())).sql,
        )
        assertEquals(
            "SELECT * FROM NostrEvent WHERE 0 ORDER BY createdAt DESC, id DESC",
            NostrEventFilterQuery.build(SynchronizationFilter(tags = mapOf("h" to emptyList()))).sql,
        )
    }

    @Test
    fun `an unconstrained filter selects everything`() {
        assertEquals(
            "SELECT * FROM NostrEvent ORDER BY createdAt DESC, id DESC",
            NostrEventFilterQuery.build(SynchronizationFilter()).sql,
        )
    }

    /** A limited filter takes the newest rows, which is the window a relay returns. */
    @Test
    fun `a limit is bound last and ordered newest first`() {
        val query = NostrEventFilterQuery.build(
            SynchronizationFilter(kinds = arrayOf(1)),
            limit = 50,
        )

        assertEquals(
            "SELECT * FROM NostrEvent WHERE kind IN (?) ORDER BY createdAt DESC, id DESC LIMIT ?",
            query.sql,
        )
        assertEquals(listOf(1L, 50L), query.boundValues())
    }

    @Test
    fun `search still matches content`() {
        val query = NostrEventFilterQuery.build(SynchronizationFilter(search = "dedun"))

        assertEquals(
            "SELECT * FROM NostrEvent WHERE content LIKE '%' || ? || '%' ORDER BY createdAt DESC, id DESC",
            query.sql,
        )
        assertEquals(listOf("dedun"), query.boundValues())
    }

    private fun androidx.room3.RoomRawQuery.boundValues(): List<Any> =
        RecordingStatement().also { getBindingFunction().invoke(it) }.bound

    /** Captures what the query binds, in order. Only the `bind*` calls are ever made. */
    private class RecordingStatement : SQLiteStatement {
        val bound = mutableListOf<Any>()

        override fun bindText(index: Int, value: String) {
            check(index == bound.size + 1) { "out of order bind at $index" }
            bound += value
        }

        override fun bindLong(index: Int, value: Long) {
            check(index == bound.size + 1) { "out of order bind at $index" }
            bound += value
        }

        override fun bindBlob(index: Int, value: ByteArray) = unsupported()
        override fun bindDouble(index: Int, value: Double) = unsupported()
        override fun bindNull(index: Int) = unsupported()
        override fun getBlob(index: Int): ByteArray = unsupported()
        override fun getDouble(index: Int): Double = unsupported()
        override fun getLong(index: Int): Long = unsupported()
        override fun getText(index: Int): String = unsupported()
        override fun isNull(index: Int): Boolean = unsupported()
        override fun getColumnCount(): Int = unsupported()
        override fun getColumnName(index: Int): String = unsupported()
        override fun getColumnType(index: Int): Int = unsupported()
        override fun step(): Boolean = unsupported()
        override fun reset() = unsupported()
        override fun clearBindings() = unsupported()
        override fun close() = unsupported()

        private fun unsupported(): Nothing = error("only bind calls are expected")
    }
}
