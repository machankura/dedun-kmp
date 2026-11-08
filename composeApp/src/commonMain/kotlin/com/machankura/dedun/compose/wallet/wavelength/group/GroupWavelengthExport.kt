package com.machankura.dedun.compose.wallet.wavelength.group

import com.vitorpamplona.quartz.nip01Core.core.HexKey

/**
 * The history as a file a group can account for.
 *
 * B8's reason for the `source` column is the whole reason this exists: **an export that silently mixes
 * what this device signed with what whoever runs the wallet merely claims is the one artefact where
 * that distinction is lost.** On the screen the provenance is in the row's own words and in the
 * operator-only section under the list; in a spreadsheet it is a column or it is nothing.
 *
 * Every other column is a field the entry already carries. Nothing is re-queried, nothing is derived,
 * and no row is left out: a reader comparing this against the screen gets the same rows in the same
 * order, which is what makes it an export rather than a report.
 */
object GroupWavelengthExport {

    /** `when` is seconds since the epoch rather than a date: a spreadsheet formats it, and a timezone here would be a guess. */
    internal val COLUMNS = listOf("when", "kind", "status", "source", "direction", "amount_sat", "fee_sat", "member", "member_name", "id")

    /**
     * [entries] as CSV, newest first as the screen lists them, with [nameOf] resolving a member's name.
     *
     * RFC 4180 quoting on every field rather than only where it is needed: a member name is free text a
     * person typed, and the first one containing a comma is how a file like this quietly becomes one
     * column short on every row below it.
     */
    fun toCsv(entries: List<GroupWavelengthEntry>, nameOf: (HexKey) -> String? = { null }): String =
        buildString {
            appendLine(COLUMNS.joinToString(",") { quote(it) })
            entries.forEach { entry ->
                appendLine(
                    listOf(
                        entry.at?.toString().orEmpty(),
                        entry.kind.name,
                        entry.status.name,
                        entry.source.name,
                        entry.direction.name,
                        entry.amountSat.toString(),
                        entry.feeSat.toString(),
                        entry.member.orEmpty(),
                        entry.member?.let(nameOf).orEmpty(),
                        entry.id,
                    ).joinToString(",") { quote(it) }
                )
            }
        }

    /** A field, quoted, with its own quotes doubled. */
    private fun quote(field: String): String = "\"" + field.replace("\"", "\"\"") + "\""
}
