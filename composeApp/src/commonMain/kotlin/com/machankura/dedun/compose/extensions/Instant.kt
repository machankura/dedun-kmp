package com.machankura.dedun.compose.extensions

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.format
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

fun Instant.toFormattedTimeAndDateString(): String {
    return toLocalDateTime(TimeZone.currentSystemDefault()).format(
        LocalDateTime.Format {


            hour()
            char(':')
            minute()
            char(' ')
            day()
            char(' ')
            monthName(
                MonthNames.ENGLISH_ABBREVIATED
            )
            char(' ')
            year()
        }
    )
}

/**
 * A chat list's clock: the least that still says which line is newer.
 *
 * The row already carries the message itself, so the timestamp only has to place it —
 * and a full date on today's message crowds out the words it belongs to. Coarser the
 * further back it goes, and never coarser than the reader can resolve: a weekday is
 * unambiguous for six days and no longer, and a bare day and month for a year.
 *
 * A timestamp ahead of [now] gets a date rather than a time. Relay clocks disagree and
 * an event can arrive stamped in the future; rendering that as "14:05" would put it in
 * a today it does not belong to.
 */
fun Instant.toChatListTimestampString(now: Instant = Clock.System.now()): String {
    val timeZone = TimeZone.currentSystemDefault()
    val dateTime = toLocalDateTime(timeZone)
    val today = now.toLocalDateTime(timeZone).date
    val daysAgo = dateTime.date.daysUntil(today)

    return when {
        daysAgo == 0 -> dateTime.format(
            LocalDateTime.Format {
                hour()
                char(':')
                minute()
            }
        )

        daysAgo == 1 -> "Yesterday"

        daysAgo in 2..6 -> dateTime.date.format(
            LocalDate.Format {
                dayOfWeek(DayOfWeekNames.ENGLISH_ABBREVIATED)
            }
        )

        dateTime.year == today.year -> dateTime.date.format(
            LocalDate.Format {
                day()
                char(' ')
                monthName(MonthNames.ENGLISH_ABBREVIATED)
            }
        )

        else -> dateTime.date.format(
            LocalDate.Format {
                day()
                char(' ')
                monthName(MonthNames.ENGLISH_ABBREVIATED)
                char(' ')
                year()
            }
        )
    }
}
