package com.machankura.dedun.compose.wallet.wavelength.group

import com.machankura.dedun.compose.database.model.GroupArkFee
import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthExitEvent
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthEntry.Kind
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthEntry.Source
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthEntry.Status
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthHistoryView.Filters
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthHistoryView.Row
import fr.acinq.bitcoin.Chain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Finding one payout among a year of renewals, which is what B8 is for.
 *
 * Pure, so none of this needs a screen: the search, the three filters and the fold are a function of
 * the entries the wallet screen already holds.
 */
class GroupWavelengthHistoryViewJvmTest {
    private val wallet = "regtest:room:0"
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)

    private fun entry(
        id: String,
        kind: Kind,
        member: String? = null,
        source: Source = Source.THIS_DEVICE,
        amountSat: Long = 1_000,
        feeSat: Long = 0,
        term: Long? = null,
    ) = GroupWavelengthEntry(
        id = id,
        kind = kind,
        status = Status.DONE,
        source = source,
        amountSat = amountSat,
        feeSat = feeSat,
        at = 1_000,
        member = member,
        direction = GroupWavelengthEntry.Direction.NONE,
        detail = if (kind == Kind.ROUND) {
            GroupWavelengthEntry.Detail.Round(fee = term?.let { GroupArkFee(wallet, id, feeSat, it, 1_000) })
        } else {
            GroupWavelengthEntry.Detail.Exit(GroupWavelengthExitEvent.Exit(Chain.Regtest, 0, 2_000, 1_000))
        },
    )

    /** The words a row shows, which the composable resolves and this supplies by hand. */
    private val words: (GroupWavelengthEntry) -> String = { e ->
        when {
            e.member == alice -> "Paid out to Zanele, confirmed"
            e.member == bob -> "Deposit from Thembi, waiting"
            e.kind == Kind.ROUND -> "Renewed the group's coins"
            else -> "Left for the onchain wallet"
        }
    }

    private val history = listOf(
        entry("p1", Kind.PAYOUT_ONCHAIN, member = alice, amountSat = 50_000),
        entry("r1", Kind.ROUND, feeSat = 300, term = 3),
        entry("r2", Kind.ROUND, feeSat = 400, term = 3),
        entry("r3", Kind.ROUND, feeSat = 500, term = 3),
        entry("d1", Kind.DEPOSIT_ONCHAIN, member = bob, amountSat = 70_000),
        entry("o1", Kind.OPERATOR, source = Source.THE_OPERATOR, amountSat = 1),
        entry("r4", Kind.ROUND, feeSat = 600, term = 4),
    )

    @Test
    fun `with nothing typed and nothing chosen, the rows are the entries with renewals folded`() {
        val rows = GroupWavelengthHistoryView.rows(history, words = words)
        assertEquals(listOf("p1", "term:3", "d1", "o1", "r4"), rows.map { it.key })
    }

    /** A term of one renewal is left alone: a fold that hides nothing is a heading in the way. */
    @Test
    fun `a term with one renewal in it is not folded`() {
        val rows = GroupWavelengthHistoryView.rows(history, words = words)
        assertTrue(rows.any { it is Row.One && it.entry.id == "r4" }, "one renewal in term 4")
    }

    @Test
    fun `the fold's total is the sum of the rows it hides`() {
        val term = GroupWavelengthHistoryView.rows(history, words = words).filterIsInstance<Row.Term>().single()
        assertEquals(3L, term.term)
        assertEquals(3, term.entries.size)
        assertEquals(1_200L, term.feeSat, "300 + 400 + 500")
        assertEquals(term.entries.sumOf { it.feeSat }, term.feeSat)
    }

    /**
     * A round inside the free refresh window leaves no fee row, and the fee row is where the term comes
     * from. Guessing which term it belonged to would put a figure into a total that nothing supports.
     */
    @Test
    fun `a round whose term this device does not hold is never folded`() {
        val free = history + entry("r5", Kind.ROUND, feeSat = 0, term = null) + entry("r6", Kind.ROUND, feeSat = 0, term = null)
        val rows = GroupWavelengthHistoryView.rows(free, words = words)
        assertEquals(listOf("r5", "r6"), rows.filterIsInstance<Row.One>().filter { it.entry.kind == Kind.ROUND && it.entry.feeSat == 0L }.map { it.entry.id })
    }

    @Test
    fun `search finds a member by the name on the row, not by their key`() {
        assertEquals(listOf("p1"), GroupWavelengthHistoryView.rows(history, query = "zanele", words = words).map { it.key })
        assertEquals(listOf("d1"), GroupWavelengthHistoryView.rows(history, query = "Thembi", words = words).map { it.key })
    }

    @Test
    fun `search finds a row by a word of its status`() {
        assertEquals(listOf("d1"), GroupWavelengthHistoryView.rows(history, query = "waiting", words = words).map { it.key })
        assertEquals(listOf("p1"), GroupWavelengthHistoryView.rows(history, query = "confirmed", words = words).map { it.key })
    }

    /** Searching for a payout must not fold the renewals that happened to match it. */
    @Test
    fun `a search that matches one renewal shows it as itself`() {
        val rows = GroupWavelengthHistoryView.rows(history, query = "Renewed", words = words)
        assertEquals(listOf("term:3", "r4"), rows.map { it.key })
    }

    @Test
    fun `each filter alone narrows to its own dimension`() {
        assertEquals(
            listOf("term:3", "r4"),
            GroupWavelengthHistoryView.rows(history, filters = Filters(kind = Kind.ROUND), words = words).map { it.key },
        )
        assertEquals(
            listOf("d1"),
            GroupWavelengthHistoryView.rows(history, filters = Filters(member = bob), words = words).map { it.key },
        )
        assertEquals(
            listOf("o1"),
            GroupWavelengthHistoryView.rows(history, filters = Filters(source = Source.THE_OPERATOR), words = words).map { it.key },
        )
    }

    @Test
    fun `two filters together narrow on both`() {
        assertEquals(
            emptyList(),
            GroupWavelengthHistoryView.rows(history, filters = Filters(kind = Kind.ROUND, member = bob), words = words).map { it.key },
        )
        assertEquals(
            listOf("d1"),
            GroupWavelengthHistoryView.rows(history, filters = Filters(kind = Kind.DEPOSIT_ONCHAIN, member = bob), words = words).map { it.key },
        )
    }

    @Test
    fun `a filter and a search together narrow on both`() {
        assertEquals(
            emptyList(),
            GroupWavelengthHistoryView.rows(history, query = "zanele", filters = Filters(kind = Kind.ROUND), words = words).map { it.key },
        )
    }

    /** A chip row with one option filters nothing, so the screen asks what there is to choose between. */
    @Test
    fun `the dimensions offered are the ones present, in a stable order`() {
        assertEquals(
            listOf(Kind.DEPOSIT_ONCHAIN, Kind.PAYOUT_ONCHAIN, Kind.ROUND, Kind.OPERATOR),
            GroupWavelengthHistoryView.kinds(history),
        )
        assertEquals(listOf(alice, bob), GroupWavelengthHistoryView.members(history), "first appearance, so the list does not reorder")
        assertEquals(listOf(Source.THIS_DEVICE, Source.THE_OPERATOR), GroupWavelengthHistoryView.sources(history))
        assertEquals(emptyList(), GroupWavelengthHistoryView.kinds(emptyList()))
    }

    @Test
    fun `nothing chosen is not a filter`() {
        assertEquals(false, Filters().any)
        assertEquals(true, Filters(member = alice).any)
    }
}
