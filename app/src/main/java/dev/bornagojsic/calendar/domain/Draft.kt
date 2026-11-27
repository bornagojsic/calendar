package dev.bornagojsic.calendar.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Full event row behind an occurrence, for the detail sheet and editor. */
data class EventDetails(
    val eventId: Long,
    val calendarId: Long,
    val calendarName: String,
    val writable: Boolean,
    val title: String,
    val location: String,
    val description: String,
    val allDay: Boolean,
    val zone: ZoneId,
    /** The series' RRULE; for an exception row, its master's. */
    val rrule: String?,
    /** Master event id; equals [eventId] unless this row is an exception. */
    val seriesId: Long,
    /** Master DTSTART. */
    val seriesStart: Instant,
    /** ORIGINAL_INSTANCE_TIME when this row is an exception, else null. */
    val originalTime: Instant?,
    val reminders: List<Int>,
    /** Whether the series has a _SYNC_ID, i.e. a sync adapter has uploaded it. */
    val synced: Boolean = true,
    /** The series' EXDATE list, raw. */
    val exdate: String? = null,
    /** The series' DURATION, raw. */
    val duration: String? = null,
    /** EVENT_COLOR_KEY: an entry of the account's palette, or null. */
    val colorKey: String? = null,
    /** EVENT_COLOR; null follows the calendar color. */
    val color: Int? = null,
) {
    val recurring get() = rrule != null
}

/** The occurrence being edited and the event row behind it. */
data class Source(val event: Event, val details: EventDetails) {
    /** The occurrence's slot in the series, which an exception keeps even after moving. */
    val originalTime: Instant get() = details.originalTime ?: event.begin
}

/**
 * Editor state. Times are wall-clock in [zone]; for all-day events only the dates count and [end] is the last day, inclusive.
 */
data class Draft(
    val calendarId: Long,
    val title: String = "",
    val allDay: Boolean = false,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val zone: ZoneId = ZoneId.systemDefault(),
    val rrule: String? = null,
    val reminders: List<Int> = listOf(10),
    val location: String = "",
    val description: String = "",
    /** See [EventDetails.colorKey]; both null follow the calendar color. */
    val colorKey: String? = null,
    val color: Int? = null,
) {
    /** DTSTART. All-day events are stored as UTC midnights. */
    fun begin(): Instant =
        if (allDay) start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant() else start.atZone(zone).toInstant()

    /** DTEND, exclusive. */
    fun endInstant(): Instant =
        if (allDay) end.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant() else end.atZone(zone).toInstant()

    /** RFC 5545 DURATION, which recurring events use instead of DTEND. */
    fun duration(): String = Duration.between(begin(), endInstant()).let {
        if (allDay) "P${it.toDays()}D" else "P${it.seconds}S"
    }

    /** Moves the start, keeping the length; a weekly rule on the old start's weekday follows it to the new one. */
    fun moveStart(to: LocalDateTime): Draft {
        val rule = rrule?.let { Rule.parse(it, zone) }
        val follows = rule != null && rule.freq == Freq.WEEKLY && rule.days == setOf(start.dayOfWeek)
        return copy(
            start = to,
            end = to.plus(Duration.between(start, end)),
            // Swap only the BYDAY value, so the rest of the rule (an exact UNTIL, WKST) stays as written.
            rrule = if (follows) rrule!!.replace(Regex("BYDAY=[^;]*"), "BYDAY=${dayCode(to.dayOfWeek)}") else rrule,
        )
    }

    /** Switches all-day or zone. Only an all-day switch rewrites the rule, since UNTIL's form depends on it. */
    fun reshape(allDay: Boolean = this.allDay, zone: ZoneId = this.zone): Draft = copy(
        allDay = allDay,
        zone = zone,
        rrule = if (allDay == this.allDay) rrule else rrule?.let { Rule.parse(it, this.zone)?.format(zone, allDay) ?: it },
    )

    /** Timed drafts compare the resolved instants: a start in a DST gap moves forward when stored. */
    val valid get() = if (allDay) !end.toLocalDate().isBefore(start.toLocalDate()) else !endInstant().isBefore(begin())

    companion object {
        fun of(src: Source): Draft {
            val d = src.details
            val zone = if (d.allDay) ZoneOffset.UTC else d.zone
            return Draft(
                calendarId = d.calendarId,
                title = d.title,
                allDay = d.allDay,
                start = LocalDateTime.ofInstant(src.event.begin, zone),
                // All-day end is exclusive in the provider, inclusive in the editor.
                end = LocalDateTime.ofInstant(if (d.allDay) src.event.end.minusSeconds(86_400).coerceAtLeast(src.event.begin) else src.event.end, zone),
                // All-day rows carry UTC; use the device zone in case the user switches to timed.
                zone = if (d.allDay) ZoneId.systemDefault() else d.zone,
                rrule = d.rrule,
                reminders = d.reminders,
                location = d.location,
                description = d.description,
                colorKey = d.colorKey,
                color = d.color,
            )
        }
    }
}
