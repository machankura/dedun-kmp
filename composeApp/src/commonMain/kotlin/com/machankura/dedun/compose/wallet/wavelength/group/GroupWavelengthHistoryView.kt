package com.machankura.dedun.compose.wallet.wavelength.group

import com.vitorpamplona.quartz.nip01Core.core.HexKey

/**
 * The history as a member reads it: searched, filtered, and with renewals folded.
 *
 * B8 of `docs/group-wavelength-redesign.md`. A group of four that has had this wallet for a year has
 * hundreds of renewal rows and a handful of payouts, and before this the only way to find a payout was
 * to scroll past the renewals. Nothing here is new information -- it is the same `entries` the wallet
 * screen already holds, in an order and a grouping a person can use.
 *
 * **A pure object, not the view model the plan named.** The plan's reason for the view model was that
 * the composable should stay a list rather than filtering inside its item lambdas, which this
 * satisfies; and a function with a fixture has a test, where a view model would have needed a harness.
 * What does live in the composable is the *typing* -- the query and the chips -- in `rememberSaveable`.
 *
 * The two things it cannot know for itself are passed in: an entry's **words** and a member's **name**
 * both come from composable string lookups ([GroupWavelengthEntryText]'s helpers and the room's
 * profiles), so the caller resolves them and this searches what it is given.
 */
object GroupWavelengthHistoryView {

    /** Nothing chosen means no filtering on that dimension: a filter with one option filters nothing. */
    data class Filters(
        val kind: GroupWavelengthEntry.Kind? = null,
        val member: HexKey? = null,
        val source: GroupWavelengthEntry.Source? = null,
    ) {
        val any: Boolean get() = kind != null || member != null || source != null
    }

    /** A row of the list: one entry, or a term's renewals folded into one. */
    sealed interface Row {
        val key: String

        data class One(val entry: GroupWavelengthEntry) : Row {
            override val key: String get() = entry.id
        }

        /**
         * Every renewal this device signed for in one operator's term, and what they cost between them.
         *
         * T10 left the question open and B8 answers it as *fold*. Reversible by deleting this branch:
         * the entries are carried whole, so an expanded fold is the rows that were always there.
         */
        data class Term(val term: Long, val feeSat: Long, val entries: List<GroupWavelengthEntry>) : Row {
            override val key: String get() = "term:$term"
        }
    }

    /**
     * [entries] searched, filtered, and folded, in the order they were given.
     *
     * Folding happens last, so a search for a payout does not fold the renewals that matched it: a term
     * row standing for one renewal would be a heading over a single line.
     */
    fun rows(
        entries: List<GroupWavelengthEntry>,
        query: String = "",
        filters: Filters = Filters(),
        words: (GroupWavelengthEntry) -> String = { "" },
    ): List<Row> {
        val matching = entries.filter { matches(it, query, filters, words) }
        return fold(matching)
    }

    private fun matches(
        entry: GroupWavelengthEntry,
        query: String,
        filters: Filters,
        words: (GroupWavelengthEntry) -> String,
    ): Boolean {
        if (filters.kind != null && entry.kind != filters.kind) return false
        if (filters.member != null && entry.member?.lowercase() != filters.member.lowercase()) return false
        if (filters.source != null && entry.source != filters.source) return false
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return true
        return words(entry).contains(trimmed, ignoreCase = true)
    }

    /**
     * Renewals in the same term become one row; everything else stays as it is.
     *
     * A term of one renewal is left alone, because a fold that hides nothing is a heading in the way.
     * A round whose term this device does not hold -- one inside the free refresh window leaves no fee
     * row, and a fee row is where the term comes from -- is never folded: guessing which term it
     * belonged to would put a figure in a total that nothing supports.
     */
    private fun fold(entries: List<GroupWavelengthEntry>): List<Row> {
        val terms = entries.groupBy { it.takeIf { e -> e.kind == GroupWavelengthEntry.Kind.ROUND }?.let(::termOf) }
            .filterKeys { it != null }
            .filterValues { it.size > 1 }
        val folded = mutableSetOf<String>()
        val rows = mutableListOf<Row>()
        for (entry in entries) {
            val term = entry.takeIf { it.kind == GroupWavelengthEntry.Kind.ROUND }?.let(::termOf)
            val group = term?.let { terms[it] }
            if (group == null) {
                rows += Row.One(entry)
                continue
            }
            if (!folded.add("term:$term")) continue
            rows += Row.Term(term, group.sumOf { it.feeSat }, group)
        }
        return rows
    }

    /** The term a round was signed under, or null where this device holds no fee row for it. */
    private fun termOf(entry: GroupWavelengthEntry): Long? =
        (entry.detail as? GroupWavelengthEntry.Detail.Round)?.fee?.term

    /** The kinds present, so a chip row with one option is not drawn. In the enum's order. */
    fun kinds(entries: List<GroupWavelengthEntry>): List<GroupWavelengthEntry.Kind> =
        GroupWavelengthEntry.Kind.entries.filter { kind -> entries.any { it.kind == kind } }

    /** The members present, in the order they first appear, so the list does not reorder as rows arrive. */
    fun members(entries: List<GroupWavelengthEntry>): List<HexKey> =
        entries.mapNotNull { it.member?.lowercase() }.distinct()

    /** Whose word each row is, which is the dimension a group auditing itself actually wants. */
    fun sources(entries: List<GroupWavelengthEntry>): List<GroupWavelengthEntry.Source> =
        GroupWavelengthEntry.Source.entries.filter { source -> entries.any { it.source == source } }
}
