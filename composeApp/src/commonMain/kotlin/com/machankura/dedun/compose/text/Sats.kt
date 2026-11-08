package com.machankura.dedun.compose.text

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.insert
import fr.acinq.bitcoin.Satoshi
import fr.acinq.phoenix.data.ExchangeRate
import kotlin.math.abs
import kotlin.math.round

/**
 * An amount in sats, its thousands grouped: `120,000`.
 *
 * Sats rather than bitcoin, everywhere the group's wallet shows an amount: a
 * signer comparing a fee to an output is comparing two integers, and a decimal
 * point eight places in is the easiest digit on the screen to misread.
 */
fun Satoshi.grouped(): String {
    val digits = sat.toString().removePrefix("-")
    val grouped = groupThousands(digits)
    return if (sat < 0) "-$grouped" else grouped
}

/** Digits with a comma every three from the right. Shared with the local-currency line below. */
private fun groupThousands(digits: String): String =
    digits.reversed().chunked(3).joinToString(",").reversed()

/**
 * This many sats in a member's own currency, or null where there is nothing honest to say.
 *
 * **Never the figure a decision reads** (B1 of `docs/group-wavelength-redesign.md`). It goes under a
 * sats figure, never instead of one: a rate is a claim about the outside world that can be stale or
 * absent, and every threshold, cap, fee limit and approval in this wallet is in sats. A member acting
 * on this line is acting on the wrong number. Null rather than a guess where there is no rate, and
 * null for a price that is zero, negative or not finite -- which is not a rate.
 *
 * The rounding is the guide's as far as the guide goes.
 * [Formatting very small amounts](https://bitcoin.design/guide/designing-products/units-and-symbols/#formatting-very-small-amounts)
 * gives eight worked examples and **every one of them is below one unit of currency**: the section is
 * headed for small amounts and says nothing about the figures this wallet mostly shows. So below one
 * unit this is the guide's rule exactly -- a value already carrying two or fewer non-zero digits keeps
 * its own digits, and any other rounds to two non-zero digits -- which turns its `$0.038878830` into
 * `~0.039` and its `0.0000431`, the value it says most libraries render as `0.00`, into `~0.000043`.
 * **At or above one unit the rule is this app's:** two decimal places, which is what a member reading a
 * balance expects, and the guide neither gives nor forbids it.
 *
 * The `~` is the guide's mark, under the guide's condition: rounding changed the value. On a converted
 * amount that is almost always true, which is the point -- the line is an estimate and says so.
 *
 * The currency is the member's own primary preference, so four members may read one balance in four
 * currencies. That is right for each of them and odd in a room, and it is question 1 of the plan.
 */
fun Satoshi.inLocalCurrency(rate: ExchangeRate.BitcoinPriceRate?): String? {
    if (rate == null || !rate.price.isFinite() || rate.price <= 0.0) return null
    val value = sat / SATS_PER_BITCOIN * rate.price
    if (!value.isFinite()) return null
    val magnitude = abs(value)
    // A figure this large is a broken rate rather than a balance, and past a double's exact integer
    // range the scaling below starts dropping digits silently.
    if (magnitude >= TOO_LARGE) return null

    var places = placesFor(magnitude)
    var rounded = roundTo(magnitude, places)
    // Rounding can cross a decade -- 0.0999 becomes 0.100 -- and two non-zero digits then need one
    // place fewer than the unrounded value asked for.
    placesFor(rounded).takeIf { it != places }?.let {
        places = it
        rounded = roundTo(magnitude, it)
    }

    // Not `rounded == magnitude`. The conversion is a double multiply, so an amount that is round to
    // the last digit arrives as 0.029999999999999998 or 246.90000000000003 and an exact comparison
    // calls it rounded -- which put a `~` on a figure that had lost nothing, and padded the guide's
    // 0.03 out to 0.030. The question this asks is whether the *display* lost anything, so the
    // tolerance is above a double's noise and far below any rounding a reader would care about.
    val exact = abs(rounded - magnitude) <= magnitude * NOISE
    val digits = fixed(rounded, places, trimTo = if (exact) MIN_PLACES else places)
    val sign = if (value < 0) "-" else ""
    val approximately = if (exact) "" else "~"
    return "$approximately$sign$digits ${rate.fiatCurrency.displayCode}"
}

private const val SATS_PER_BITCOIN = 100_000_000.0

/** Two decimal places, because cents are hundredths. The guide's minimum, and this app's rule above one unit. */
private const val MIN_PLACES = 2

/** Deep enough for two non-zero digits of a value a hundred-millionth of a unit, and no deeper. */
private const val MAX_PLACES = 12

/** Ten trillion of any currency. Past it a double no longer holds the scaled integer exactly. */
private const val TOO_LARGE = 1e13

/**
 * What counts as the same figure. A double carries about 15 significant digits, so a relative
 * difference this small is arithmetic noise rather than a figure that was rounded.
 */
private const val NOISE = 1e-9

/** [MIN_PLACES] at or above one unit; below it, the place that leaves two non-zero digits. */
private fun placesFor(magnitude: Double): Int {
    var places = MIN_PLACES
    var scaled = magnitude
    while (scaled > 0.0 && scaled < 0.1 && places < MAX_PLACES) {
        scaled *= 10
        places++
    }
    return places
}

private fun roundTo(magnitude: Double, places: Int): Double {
    val scale = scaleOf(places)
    return round(magnitude * scale) / scale
}

private fun scaleOf(places: Int): Double {
    var scale = 1.0
    repeat(places) { scale *= 10 }
    return scale
}

/**
 * [magnitude] with [places] digits after the point and its thousands grouped, then trailing zeros
 * dropped down to [trimTo]. The trim is what keeps the guide's `0.03` from being padded to `0.030`:
 * a value that is already round shows the digits it has, and a rounded one shows the place it was
 * rounded to.
 */
private fun fixed(magnitude: Double, places: Int, trimTo: Int): String {
    val scale = scaleOf(places).toLong()
    val units = round(magnitude * scaleOf(places)).toLong()
    val whole = groupThousands((units / scale).toString())
    var fraction = (units % scale).toString().padStart(places, '0')
    while (fraction.length > trimTo && fraction.endsWith("0")) fraction = fraction.dropLast(1)
    return if (fraction.isEmpty()) whole else "$whole.$fraction"
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
