package dev.bornagojsic.calendar.domain

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
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

    /** All-day or 24h+: drawn as one bar over its days, not as a block in the time grid. */
    val isBar get() = allDay || Duration.between(begin, end) >= Duration.ofDays(1)
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
        .filter { !it.isBar && it.begin < dayEnd && (it.end > dayStart || it.begin == dayStart) }
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

/** An event drawn in stacking [slot] over columns [startCol]..[endCol] of a row of days. */
data class Bar(val event: Event, val slot: Int, val startCol: Int, val endCol: Int)

/**
 * Stacks [events] over consecutive [days]. Bars ([Event.isBar]) come first and keep one slot over
 * all their days, cut at the row's edges; other events get a one-day piece per day, in the free slots.
 */
fun layoutBars(events: List<Event>, days: List<LocalDate>, zone: ZoneId): List<Bar> {
    fun col(d: LocalDate) = ChronoUnit.DAYS.between(days.first(), d).toInt()
    val pieces = events
        .flatMap { e ->
            val r = e.days(zone)
            val cols = maxOf(col(r.start), 0)..minOf(col(r.endInclusive), days.size - 1)
            when {
                cols.isEmpty() -> emptyList()
                e.isBar -> listOf(e to cols)
                else -> cols.map { e to it..it }
            }
        }
        .sortedWith(compareBy({ !it.first.isBar }, { it.second.first }, { -it.second.last }, { it.first.begin }))
    val taken = List(days.size) { mutableSetOf<Int>() }
    return pieces.map { (e, cols) ->
        val slot = generateSequence(0) { it + 1 }.first { s -> cols.none { s in taken[it] } }
        cols.forEach { taken[it] += slot }
        Bar(e, slot, cols.first, cols.last)
    }
}

/** [first] is the week's first day: the user's setting, or the locale's (see `Prefs`). */
fun weekStart(date: LocalDate, first: DayOfWeek): LocalDate = date.with(TemporalAdjusters.previousOrSame(first))

/** User settings; a null [weekStart] or [clock24] follows the phone's locale. */
data class Prefs(
    val weekStart: DayOfWeek? = null,
    val clock24: Boolean? = null,
    /** Default reminders of new events, in minutes before. */
    val timedReminders: List<Int> = listOf(10),
    val allDayReminders: List<Int> = listOf(10),
    val eventMinutes: Int = 60,
    val snoozeMinutes: Int = 60,
    /** Keys (`type/name`) of accounts whose calendars are left out of the drawer, views and search. */
    val hiddenAccounts: Set<String> = emptySet(),
) {
    fun firstDayOfWeek(locale: Locale = Locale.getDefault()): DayOfWeek = weekStart ?: WeekFields.of(locale).firstDayOfWeek

    fun reminders(allDay: Boolean) = if (allDay) allDayReminders else timedReminders

    /** A new event switched to or from all day swaps to the other default reminders, unless the user changed them. */
    fun followDefaults(old: Draft, new: Draft, edited: Boolean): Draft =
        if (!edited && new.allDay != old.allDay) new.copy(reminders = reminders(new.allDay)) else new
}
