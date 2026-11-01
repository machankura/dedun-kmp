package com.machankura.dedun.compose.text

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.insert
import fr.acinq.bitcoin.Satoshi

/**
 * An amount in sats, its thousands grouped: `120,000`.
 *
 * Sats rather than bitcoin, everywhere the group's wallet shows an amount: a
 * signer comparing a fee to an output is comparing two integers, and a decimal
 * point eight places in is the easiest digit on the screen to misread.
 */
fun Satoshi.grouped(): String {
    val digits = sat.toString().removePrefix("-")
    val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
    return if (sat < 0) "-$grouped" else grouped
}

/** What a member typed as an amount in sats, read -- or the reason it cannot be. */
sealed interface SatsInput {
    data object Empty : SatsInput
    data class Sats(val amount: Satoshi) : SatsInput
    /** A decimal separator: a sat is the smallest thing there is to send. */
    data object NotWhole : SatsInput
    /** More than 21,000,000 bitcoin. */
    data object TooLarge : SatsInput
    /** Letters, or grouping that groups nothing: `40,0000`, `4 0`. */
    data object Unreadable : SatsInput
}

/** Every sat there will ever be: 21,000,000 bitcoin. */
val MAX_SATS: Satoshi = Satoshi(2_100_000_000_000_000)

/**
 * Thousands separators people paste: a comma, a space and its two no-break
 * forms -- the last is how a South African locale groups -- an apostrophe and
 * an underscore.
 */
private val GROUPING = setOf(',', ' ', ' ', ' ', '\'', '_')

/**
 * [text] as sats, refusing anything it would have to guess at.
 *
 * Separators are dropped only where they group thousands. A `.` is a decimal
 * point and refused, and so is a comma with one or two digits after it,
 * because `40,5` is forty and a half where commas are decimal points. Dropping
 * either is how `21000.5` used to become 210,005 sats.
 */
fun parseSats(text: CharSequence): SatsInput {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return SatsInput.Empty
    if ('.' in trimmed) return SatsInput.NotWhole
    // Ascii digits only: `isDigit` admits every script's digits, and so does the JVM's `toLong`.
    if (trimmed.any { it !in '0'..'9' && it !in GROUPING }) return SatsInput.Unreadable

    val groups = mutableListOf(StringBuilder())
    val separators = mutableListOf<Char>()
    for (c in trimmed) {
        if (c in '0'..'9') {
            groups.last().append(c)
        } else {
            separators += c
            groups += StringBuilder()
        }
    }

    if (groups.size > 1) {
        val leading = groups.dropLast(1)
        if (separators.last() == ',' && groups.last().length in 1..2 && leading.all { it.isNotEmpty() }) {
            return SatsInput.NotWhole
        }
        if (groups.first().length !in 1..3 || groups.drop(1).any { it.length != 3 }) return SatsInput.Unreadable
    }

    // Counted before it is a number, so a long paste is "too large" rather than an overflow.
    val significant = groups.joinToString("").trimStart('0')
    if (significant.length > MAX_SATS.sat.toString().length) return SatsInput.TooLarge
    val sats = Satoshi(significant.ifEmpty { "0" }.toLong())
    return if (sats > MAX_SATS) SatsInput.TooLarge else SatsInput.Sats(sats)
}

/**
 * An amount field's digits shown grouped as [grouped] writes them, so what is
 * typed reads the way the preview and the approval screen will say it.
 *
 * Display only: the field holds what was typed. Anything but plain digits is
 * shown as it is, for [parseSats] to accept or refuse -- regrouping `40,5`
 * would show it as a number it is not.
 */
val GroupedSats: OutputTransformation = OutputTransformation {
    if (length > 3 && asCharSequence().all { it in '0'..'9' }) {
        var index = length - 3
        while (index > 0) {
            insert(index, ",")
            index -= 3
        }
    }
}
