package com.machankura.dedun.compose.extensions

import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * The clock on a chat list row. It has one job -- placing a message relative to now --
 * and each rung is only unambiguous over a bounded span: a bare time means nothing on
 * a message from last week, a weekday means nothing past six days, and a day and month
 * mean nothing past a year. Reading a rung too far is the failure mode, and it is silent.
 *
 * Every case is anchored to the local day rather than to a fixed instant, because the
 * boundaries are local midnights and the test would otherwise pass or fail on the
 * machine's zone.
 */
class ChatListTimestampTest {

    private val timeZone = TimeZone.currentSystemDefault()

    /** Midday today, local, so no case lands on a boundary by accident. */
    private val now = Instant.parse("2026-09-06T00:00:00Z")
        .toLocalDateTime(timeZone).date.atStartOfDayIn(timeZone) + 12.hours

    private fun daysBefore(days: Int) = now - days.days

    @Test
    fun `today shows the time of day`() {
        val formatted = daysBefore(0).toChatListTimestampString(now)

        assertEquals(
            now.toLocalDateTime(timeZone).let {
                "${it.hour.toString().padStart(2, '0')}:${it.minute.toString().padStart(2, '0')}"
            },
            formatted
        )
    }

    @Test
    fun `yesterday is named rather than dated`() {
        assertEquals("Yesterday", daysBefore(1).toChatListTimestampString(now))
    }

    /**
     * Two through six days. A seventh would be this weekday again, which reads as today.
     */
    @Test
    fun `the rest of the last week shows a weekday`() {
        (2..6).forEach { days ->
            val formatted = daysBefore(days).toChatListTimestampString(now)

            assertEquals(3, formatted.length, "$days days ago should be an abbreviated weekday")
            assertTrue(
                formatted.none { it.isDigit() },
                "$days days ago should be a weekday, not a date: $formatted"
            )
        }
    }

    /** A week out the weekday has stopped meaning anything, so the date takes over. */
    @Test
    fun `beyond a week shows a day and month`() {
        val formatted = daysBefore(7).toChatListTimestampString(now)
        val date = daysBefore(7).toLocalDateTime(timeZone).date

        assertEquals("${date.day} ${date.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)}", formatted)
    }

    /** Past a year the day and month repeat, so the year has to be said. */
    @Test
    fun `a message from another year carries its year`() {
        val formatted = daysBefore(400).toChatListTimestampString(now)
        val date = daysBefore(400).toLocalDateTime(timeZone).date

        assertTrue(
            formatted.endsWith(" ${date.year}"),
            "a message from ${date.year} should say so: $formatted"
        )
    }

    /**
     * Relay clocks disagree and an event can arrive stamped ahead of this device. Showing
     * it as a bare time would file it under a today it does not belong to.
     */
    @Test
    fun `a timestamp from the future gets a date rather than a time`() {
        val formatted = (now + 2.days).toChatListTimestampString(now)

        assertTrue(':' !in formatted, "a future timestamp should not read as a time today: $formatted")
    }
}
