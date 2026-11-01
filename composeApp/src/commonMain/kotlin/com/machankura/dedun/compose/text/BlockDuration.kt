package com.machankura.dedun.compose.text

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A number of blocks said in time, roughly -- `docs/guardian-delay.md`, D5.
 *
 * Always beside the block count, never instead of it: blocks are what a
 * guardian's leaf counts, and ten minutes is the target, not a promise, so a
 * delay of 144 blocks can take twenty hours or thirty. Hence *about*.
 *
 * The rule:
 *
 *  1. within 5% of exactly one hour, day, month or year, that unit, singular --
 *     6 blocks is *about 1 hour*, 4,320 *about 1 month*;
 *  2. otherwise the largest of years, months, days, hours and minutes in which
 *     the delay comes to at least 2, rounded -- 216 blocks is *about 36 hours*,
 *     65,535 *about 15 months*.
 *
 * Years therefore appear only near exactly one year: the longest delay a leaf
 * can carry is 1.25 years, and *about 15 months* says more than *about 1 year*.
 */
object BlockDuration {
    /** Bitcoin's target, and the testnets' and signet's. Regtest has none. */
    const val MINUTES_PER_BLOCK: Long = 10L

    /** The units, each with its length in minutes: a month is a twelfth of a Julian year. */
    enum class Unit(val minutes: Double) {
        MINUTES(1.0),
        HOURS(60.0),
        DAYS(1_440.0),
        MONTHS(43_830.0),
        YEARS(525_960.0),
    }

    data class Approximate(val count: Int, val unit: Unit)

    /** How close to exactly one of a unit a delay has to be to be said as one. */
    private const val ONE_TOLERANCE = 0.05

    fun approximate(blocks: Long): Approximate {
        require(blocks > 0) { "a duration is at least one block, not $blocks" }
        val minutes = (blocks * MINUTES_PER_BLOCK).toDouble()
        val largestFirst = Unit.entries.reversed()

        largestFirst.filter { it != Unit.MINUTES }.forEach { unit ->
            if (abs(minutes / unit.minutes - 1.0) <= ONE_TOLERANCE) return Approximate(1, unit)
        }
        largestFirst.forEach { unit ->
            val count = minutes / unit.minutes
            if (count >= 2.0) return Approximate(count.roundToInt(), unit)
        }
        return Approximate(minutes.roundToInt(), Unit.MINUTES)
    }
}

/** A count of blocks, its thousands grouped: `65,535`. */
fun Long.groupedBlocks(): String = toString().reversed().chunked(3).joinToString(",").reversed()

/**
 * *144 blocks, about 1 day*, in fixed English: for the words that are not
 * composables -- a proposal's summary -- as the rest of `ProposedEvent` is.
 * Screens use `delayText`, which reads the catalogue.
 */
fun BlockDuration.describe(blocks: Long): String {
    val approximate = approximate(blocks)
    val unit = approximate.unit.name.lowercase().let { if (approximate.count == 1) it.removeSuffix("s") else it }
    val count = if (blocks == 1L) "1 block" else "${blocks.groupedBlocks()} blocks"
    return "$count, about ${approximate.count} $unit"
}
