package com.machankura.dedun.compose.wallet.wavelength.group

import com.machankura.dedun.compose.nostr.wavelength.GroupWavelengthExitEvent
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthEntry.Kind
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthEntry.Source
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthEntry.Status
import fr.acinq.bitcoin.Chain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The export, read back as a file rather than as a string.
 *
 * The `source` column is why this exists (B8): on the screen a row's provenance is in its own words and
 * in the operator-only section under the list, and in a spreadsheet it is a column or it is nothing.
 * The quoting is why it is tested: a member name is free text somebody typed, and the first one with a
 * comma in it is how a file like this quietly becomes one column short on every row below.
 */
class GroupWavelengthExportJvmTest {
    private val alice = "a".repeat(64)

    private fun entry(
        id: String,
        kind: Kind = Kind.PAYOUT_ONCHAIN,
        source: Source = Source.THIS_DEVICE,
        member: String? = alice,
        amountSat: Long = 50_000,
        feeSat: Long = 300,
        at: Long? = 1_700_000_000,
    ) = GroupWavelengthEntry(
        id = id,
        kind = kind,
        status = Status.DONE,
        source = source,
        amountSat = amountSat,
        feeSat = feeSat,
        at = at,
        member = member,
        direction = GroupWavelengthEntry.Direction.OUT,
        detail = GroupWavelengthEntry.Detail.Exit(GroupWavelengthExitEvent.Exit(Chain.Regtest, 0, 2_000, 1_000)),
    )

    /** Each row read back as its fields, so the assertions are about values rather than about commas. */
    private fun rows(csv: String): List<List<String>> =
        csv.trim().lines().map { line ->
            val fields = mutableListOf<String>()
            val field = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> quoted = !quoted
                    c == ',' && !quoted -> { fields += field.toString(); field.clear() }
                    else -> field.append(c)
                }
                i++
            }
            fields += field.toString()
            fields
        }

    @Test
    fun `the header is the columns, and every row has one field per column`() {
        val csv = GroupWavelengthExport.toCsv(listOf(entry("p1"), entry("r1", kind = Kind.ROUND, member = null)))
        val read = rows(csv)
        assertEquals(GroupWavelengthExport.COLUMNS, read.first())
        assertEquals(3, read.size)
        read.forEach { assertEquals(GroupWavelengthExport.COLUMNS.size, it.size) }
    }

    /** Whose word each row is, which a reader of the file cannot reconstruct from anything else in it. */
    @Test
    fun `every source round-trips into its own column`() {
        val all = Source.entries.mapIndexed { i, source -> entry("e$i", source = source) }
        val read = rows(GroupWavelengthExport.toCsv(all)).drop(1)
        val column = GroupWavelengthExport.COLUMNS.indexOf("source")
        assertEquals(Source.entries.map { it.name }, read.map { it[column] })
    }

    @Test
    fun `a member name with a comma in it stays one field`() {
        val csv = GroupWavelengthExport.toCsv(listOf(entry("p1"))) { "Ngako, Kgothatso" }
        val read = rows(csv)
        assertEquals(GroupWavelengthExport.COLUMNS.size, read[1].size, "still one field per column")
        assertEquals("Ngako, Kgothatso", read[1][GroupWavelengthExport.COLUMNS.indexOf("member_name")])
    }

    @Test
    fun `a member name with a quote in it keeps the quote`() {
        val csv = GroupWavelengthExport.toCsv(listOf(entry("p1"))) { "the \"treasurer\"" }
        val read = rows(csv)
        assertEquals("the \"treasurer\"", read[1][GroupWavelengthExport.COLUMNS.indexOf("member_name")])
        assertEquals(GroupWavelengthExport.COLUMNS.size, read[1].size)
    }

    @Test
    fun `a row nobody holds a time or a member for is empty in those columns, not absent`() {
        val csv = GroupWavelengthExport.toCsv(listOf(entry("r1", kind = Kind.ROUND, member = null, at = null)))
        val read = rows(csv)[1]
        assertEquals("", read[GroupWavelengthExport.COLUMNS.indexOf("when")])
        assertEquals("", read[GroupWavelengthExport.COLUMNS.indexOf("member")])
        assertEquals("", read[GroupWavelengthExport.COLUMNS.indexOf("member_name")])
        assertEquals(GroupWavelengthExport.COLUMNS.size, read.size)
    }

    @Test
    fun `the amounts and the fee are the entry's own, in sats`() {
        val read = rows(GroupWavelengthExport.toCsv(listOf(entry("p1", amountSat = 50_000, feeSat = 300))))[1]
        assertEquals("50000", read[GroupWavelengthExport.COLUMNS.indexOf("amount_sat")])
        assertEquals("300", read[GroupWavelengthExport.COLUMNS.indexOf("fee_sat")])
    }

    @Test
    fun `an empty history is a header and nothing else`() {
        val csv = GroupWavelengthExport.toCsv(emptyList())
        assertEquals(1, csv.trim().lines().size)
        assertTrue(csv.startsWith("\"when\""))
    }
}
