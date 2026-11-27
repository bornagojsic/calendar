package dev.bornagojsic.calendar.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/** One occurrence of an event, as expanded by `CalendarContract.Instances`. */
data class Event(
    val id: Long,
    val eventId: Long,
    val title: String,
    val begin: Instant,
    val end: Instant,
    val allDay: Boolean,
    val color: Int,
) {
    /** First and last calendar day this occurrence covers. */
    fun days(zone: ZoneId): ClosedRange<LocalDate> {
        // All-day events are stored as UTC midnights, whatever the device zone.
        val z = if (allDay) ZoneOffset.UTC else zone
        val first = begin.atZone(z).toLocalDate()
        // End is exclusive: an event ending at midnight doesn't cover the next day.
        val last = if (end > begin) end.minusNanos(1).atZone(z).toLocalDate() else first
        return first..last
    }
}

/** A timed event placed in a day column. Minutes are from local midnight, clipped to the day. */
data class Placed(val event: Event, val startMin: Int, val endMin: Int, val col: Int, val cols: Int)

/** Shortest block drawn in the time grid, so zero-length events stay visible and tappable. */
const val MIN_BLOCK_MINUTES = 30

/** Lays out one day's timed events into side-by-side columns where they overlap. */
fun layoutDay(events: List<Event>, day: LocalDate, zone: ZoneId): List<Placed> {
    val dayStart = day.atStartOfDay(zone).toInstant()
    val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
    fun minutes(t: Instant) = t.atZone(zone).toLocalTime().toSecondOfDay() / 60
    val items = events
        .filter { !it.allDay && it.begin < dayEnd && (it.end > dayStart || it.begin == dayStart) }
        .map {
            val start = if (it.begin <= dayStart) 0 else minutes(it.begin)
            val end = if (it.end >= dayEnd) 24 * 60 else minutes(it.end)
            Triple(it, start, maxOf(end, start + MIN_BLOCK_MINUTES).coerceAtMost(24 * 60))
        }
        .sortedWith(compareBy({ it.second }, { -it.third }))

    val result = mutableListOf<Placed>()
    val cluster = mutableListOf<Placed>()
    val colEnds = mutableListOf<Int>()
    var clusterEnd = -1
    fun flush() {
        result += cluster.map { it.copy(cols = colEnds.size) }
        cluster.clear()
        colEnds.clear()
    }
    for ((event, start, end) in items) {
        if (start >= clusterEnd) flush()
        var col = colEnds.indexOfFirst { it <= start }
        if (col < 0) {
            col = colEnds.size
            colEnds += end
        } else {
            colEnds[col] = end
        }
        cluster += Placed(event, start, end, col, 0)
        clusterEnd = maxOf(clusterEnd, end)
    }
    flush()
    return result
}

/** [first] is the week's first day: the user's setting, or the locale's (see `Prefs`). */
fun weekStart(date: LocalDate, first: DayOfWeek): LocalDate = date.with(TemporalAdjusters.previousOrSame(first))

/** User overrides from Settings; null follows the phone's locale. */
data class Prefs(val weekStart: DayOfWeek? = null, val clock24: Boolean? = null) {
    fun firstDayOfWeek(locale: Locale = Locale.getDefault()): DayOfWeek = weekStart ?: WeekFields.of(locale).firstDayOfWeek
}
