package com.machankura.dedun.compose.database.query

import androidx.room3.RoomRawQuery
import androidx.sqlite.SQLiteStatement
import com.machankura.dedun.compose.database.model.types.SynchronizationFilter
import kotlinx.serialization.json.Json

/**
 * Translates a [SynchronizationFilter] into the SQL that selects exactly the events a relay
 * would return for the same NIP-01 filter.
 *
 * Negentropy compares two sets that are both defined by the *same* filter: ours, built here,
 * and the relay's, built from the filter carried in NEG-OPEN. Any constraint we fail to apply
 * locally makes our set a superset of the relay's, and every extra row comes back as an id the
 * relay is "missing" — which this app then schedules as a broadcast. Any constraint we apply
 * more tightly makes it a subset, and the difference comes back as ids to re-download that we
 * already have. So the value here is not just tidiness: a filter clause that is silently
 * dropped turns reconciliation into busywork in both directions.
 *
 * This replaced a chain of hand-written special cases that between them could not express
 * `until` at all, ignored `tags` whenever `authors` was also set, and fell back to
 * "kind 1 text notes" for any filter shape it did not recognise.
 *
 * Semantics mirror quartz's `FilterMatcher`, which is what the relays this app talks to
 * implement:
 *  - `ids`/`authors`/`kinds`: membership; a present-but-empty list matches nothing.
 *  - `tags`: AND between tag names, OR between the values of one name.
 *  - `tagsAll`: AND between tag names, AND between the values of one name.
 *  - `since`/`until`: **inclusive** on both ends.
 */
object NostrEventFilterQuery {

    /** Sentinel for "no limit", matching SQLite's treatment of a negative LIMIT. */
    const val NO_LIMIT: Int = -1

    fun build(
        filter: SynchronizationFilter,
        limit: Int = NO_LIMIT,
    ): RoomRawQuery {
        val clauses = mutableListOf<String>()
        val bindings = mutableListOf<Binding>()

        filter.ids?.let { clauses += inClause("id", it.asList(), bindings) }
        filter.authors?.let { clauses += inClause("pubKey", it.asList(), bindings) }
        filter.kinds?.let { kinds ->
            clauses += if (kinds.isEmpty()) {
                MATCHES_NOTHING
            } else {
                bindings += kinds.map { Binding.Number(it.toLong()) }
                "kind IN (${placeholders(kinds.size)})"
            }
        }

        // `since`/`until` are inclusive per NIP-01. The queries this replaced used a strict
        // `createdAt > :since`, so an event stamped exactly on the boundary was in the relay's
        // set and not in ours, and `until` was sent to the relay but never applied here at all.
        filter.since?.let {
            clauses += "createdAt >= ?"
            bindings += Binding.Number(it.epochSeconds)
        }
        filter.until?.let {
            clauses += "createdAt <= ?"
            bindings += Binding.Number(it.epochSeconds)
        }

        filter.search?.let {
            clauses += "content LIKE '%' || ? || '%'"
            bindings += Binding.Text(it)
        }

        // AND between tag names, OR between the values of one name.
        filter.tags?.forEach { (name, values) ->
            clauses += if (values.isEmpty()) {
                MATCHES_NOTHING
            } else {
                values.joinToString(separator = " OR ", prefix = "(", postfix = ")") { value ->
                    bindings += Binding.Text(tagPattern(name, value))
                    TAG_LIKE
                }
            }
        }

        // AND between tag names, AND between the values of one name.
        filter.tagsAll?.forEach { (name, values) ->
            values.forEach { value ->
                bindings += Binding.Text(tagPattern(name, value))
                clauses += TAG_LIKE
            }
        }

        val where = if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND ")

        // Newest-first, which is the window a relay hands back when a filter carries a limit.
        // The previous queries ordered ascending under the same limit and so returned the
        // OLDEST rows instead.
        val sql = buildString {
            append("SELECT * FROM NostrEvent")
            append(where)
            append(" ORDER BY createdAt DESC, id DESC")
            if (limit >= 0) {
                append(" LIMIT ?")
            }
        }
        if (limit >= 0) {
            bindings += Binding.Number(limit.toLong())
        }

        val boundValues = bindings.toList()
        return RoomRawQuery(sql) { statement ->
            boundValues.forEachIndexed { index, binding ->
                binding.bindTo(statement, index + 1)
            }
        }
    }

    private const val MATCHES_NOTHING = "0"

    /**
     * Tags are stored as the compact JSON of the tag array, e.g. `[["p","<hex>"],["e","<hex>"]]`,
     * so a tag is matched by looking for the `["<name>","<value>"` fragment. Anchoring on both
     * the name and the closing quote of the value is what keeps this from matching the same hex
     * sitting in an unrelated tag position — the substring scan it replaced (`tags LIKE
     * '%<pubkey>%'`) matched a pubkey anywhere in the row, including inside `e` tags.
     */
    private const val TAG_LIKE = "tags LIKE ? ESCAPE '\\'"

    private fun tagPattern(name: String, value: String): String {
        // Encode through the same serializer that wrote the column so any escaping matches,
        // then drop the closing `]` so trailing tag elements (relay hint, marker) still match.
        val fragment = Json.encodeToString(listOf(name, value)).dropLast(1)
        return "%${escapeLike(fragment)}%"
    }

    /** Keeps a `%`/`_` inside a tag value from silently widening the match. */
    private fun escapeLike(value: String): String =
        buildString(value.length) {
            value.forEach { char ->
                if (char == '\\' || char == '%' || char == '_') append('\\')
                append(char)
            }
        }

    private fun inClause(
        column: String,
        values: List<String>,
        bindings: MutableList<Binding>,
    ): String {
        if (values.isEmpty()) return MATCHES_NOTHING
        bindings += values.map { Binding.Text(it) }
        return "$column IN (${placeholders(values.size)})"
    }

    private fun placeholders(count: Int) = List(count) { "?" }.joinToString(", ")

    private sealed interface Binding {
        fun bindTo(statement: SQLiteStatement, index: Int)

        data class Text(val value: String) : Binding {
            override fun bindTo(statement: SQLiteStatement, index: Int) = statement.bindText(index, value)
        }

        data class Number(val value: Long) : Binding {
            override fun bindTo(statement: SQLiteStatement, index: Int) = statement.bindLong(index, value)
        }
    }
}
