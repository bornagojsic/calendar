package dev.bornagojsic.calendar.sync

import android.provider.CalendarContract.Events
import dev.bornagojsic.calendar.domain.exdate
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.Parameter
import net.fortuna.ical4j.model.ParameterList
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.TimeZoneRegistryFactory
import net.fortuna.ical4j.model.component.VAlarm
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.parameter.Related
import net.fortuna.ical4j.model.parameter.TzId
import net.fortuna.ical4j.model.parameter.Value
import net.fortuna.ical4j.model.property.Action
import net.fortuna.ical4j.model.property.DateListProperty
import net.fortuna.ical4j.model.property.Description
import net.fortuna.ical4j.model.property.DtEnd
import net.fortuna.ical4j.model.property.DtStamp
import net.fortuna.ical4j.model.property.DtStart
import net.fortuna.ical4j.model.property.ExDate
import net.fortuna.ical4j.model.property.Location
import net.fortuna.ical4j.model.property.ProdId
import net.fortuna.ical4j.model.property.RDate
import net.fortuna.ical4j.model.property.RRule
import net.fortuna.ical4j.model.property.RecurrenceId
import net.fortuna.ical4j.model.property.Status
import net.fortuna.ical4j.model.property.Summary
import net.fortuna.ical4j.model.property.Trigger
import net.fortuna.ical4j.model.property.Uid
import net.fortuna.ical4j.model.property.immutable.ImmutableVersion
import java.io.StringReader
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.Period
import java.time.temporal.Temporal
import java.time.temporal.TemporalAmount
import kotlin.jvm.optionals.getOrNull

/** One provider Events row (keyed by `Events` column names) and its alert reminders in minutes. */
data class EventRow(val values: Map<String, Any?>, val reminders: List<Int> = emptyList()) {
    operator fun get(column: String): Any? = values[column]
    fun long(column: String) = (values[column] as? Number)?.toLong()
    fun string(column: String) = values[column]?.toString()
}

/** Properties the provider rows hold; everything else in a stored VEVENT is kept as is on upload. */
private val MAPPED = setOf(
    Property.UID, Property.DTSTAMP, Property.DTSTART, Property.DTEND, Property.DURATION, Property.RRULE, Property.RDATE,
    Property.EXDATE, Property.EXRULE, Property.SUMMARY, Property.LOCATION, Property.DESCRIPTION, Property.STATUS,
    Property.RECURRENCE_ID,
)

/**
 * The VEVENTs of an iCalendar resource as provider rows: the master first, then its exceptions (which carry
 * ORIGINAL_INSTANCE_TIME). Times without a zone are read in [floating].
 */
fun parseEvents(ics: String, floating: ZoneId = ZoneId.systemDefault()): List<EventRow> {
    val events = CalendarBuilder().build(StringReader(ics)).getComponents<VEvent>(Component.VEVENT)
    val master = events.firstOrNull { it.recurrenceId == null } ?: events.firstOrNull() ?: return emptyList()
    val masterAllDay = master.dtStart?.date is LocalDate
    return listOf(row(master, floating, master = true)) +
        events.filter { it !== master && it.recurrenceId != null }.map { e ->
            val rows = row(e, floating, master = false)
            rows.copy(values = rows.values + mapOf(
                Events.ORIGINAL_INSTANCE_TIME to instant(e.recurrenceId!!.date, floating).toEpochMilli(),
                Events.ORIGINAL_ALL_DAY to if (masterAllDay) 1 else 0,
            ))
        }
}

private val VEvent.dtStart get() = getProperty<DtStart<Temporal>>(Property.DTSTART).getOrNull()
private val VEvent.recurrenceId get() = getProperty<RecurrenceId<Temporal>>(Property.RECURRENCE_ID).getOrNull()
private fun VEvent.text(name: String) = getProperty<Property>(name).getOrNull()?.value

private fun row(e: VEvent, floating: ZoneId, master: Boolean): EventRow {
    val start = e.dtStart?.date ?: error("VEVENT without DTSTART")
    val allDay = start is LocalDate
    val begin = instant(start, floating)
    val end = e.getProperty<DtEnd<Temporal>>(Property.DTEND).getOrNull()?.date?.let { instant(it, floating) }
        ?: e.getProperty<net.fortuna.ical4j.model.property.Duration>(Property.DURATION).getOrNull()?.duration
            ?.let { runCatching { instant(start.plus(it), floating) }.getOrElse { _ -> begin.plus(Duration.from(it)) } }
        ?: if (allDay) begin.plus(Duration.ofDays(1)) else begin
    val rrule = e.text(Property.RRULE).takeIf { master }
    val rdate = dates(e.getProperties(Property.RDATE), floating, allDay).takeIf { master }
    val recurring = rrule != null || rdate != null
    val reminders = e.alarms.mapNotNull { minutes(it) }.distinct().sorted()
    return EventRow(
        mapOf(
            Events.UID_2445 to e.text(Property.UID),
            Events.TITLE to e.text(Property.SUMMARY),
            Events.EVENT_LOCATION to e.text(Property.LOCATION),
            Events.DESCRIPTION to e.text(Property.DESCRIPTION),
            Events.DTSTART to begin.toEpochMilli(),
            Events.ALL_DAY to if (allDay) 1 else 0,
            Events.EVENT_TIMEZONE to if (allDay) "UTC" else zoneOf(e.dtStart!!, floating),
            Events.RRULE to rrule,
            Events.RDATE to rdate,
            Events.EXDATE to dates(e.getProperties(Property.EXDATE), floating, allDay).takeIf { recurring },
            // Recurring rows take DURATION instead of DTEND.
            Events.DTEND to if (recurring) null else end.toEpochMilli(),
            Events.DURATION to if (!recurring) null else Duration.between(begin, end).let {
                if (allDay) "P${it.toDays()}D" else "P${it.seconds}S"
            },
            Events.STATUS to when (e.text(Property.STATUS)) {
                "CONFIRMED" -> Events.STATUS_CONFIRMED
                "TENTATIVE" -> Events.STATUS_TENTATIVE
                "CANCELLED" -> Events.STATUS_CANCELED
                else -> null
            },
            Events.HAS_ALARM to if (reminders.isEmpty()) 0 else 1,
        ),
        reminders,
    )
}

/** Minutes before the start, for an alarm relative to the start; other alarms aren't kept. */
private fun minutes(alarm: VAlarm): Int? {
    // shortcut: absolute and end-relative triggers are dropped, since Reminders only hold minutes before start.
    val trigger = alarm.getProperty<Trigger>(Property.TRIGGER).getOrNull() ?: return null
    if (trigger.isAbsolute || trigger.getParameter<Related>(Parameter.RELATED).getOrNull() == Related.END) return null
    return (-Duration.from(trigger.duration).toMinutes()).toInt().takeIf { it >= 0 }
}

/** EXDATE/RDATE lists in the provider's form: comma-separated UTC date-times, or dates for all-day events. */
private fun dates(props: List<Property>, floating: ZoneId, allDay: Boolean): String? =
    props.filterIsInstance<DateListProperty<Temporal>>().flatMap { it.dates }
        .map { exdate(instant(it, floating), allDay) }
        .takeIf { it.isNotEmpty() }?.joinToString(",")

/** DATE values are UTC midnights, like the provider's all-day events. */
private fun instant(t: Temporal, floating: ZoneId): Instant = when (t) {
    is LocalDate -> t.atStartOfDay(ZoneOffset.UTC).toInstant()
    is LocalDateTime -> t.atZone(floating).toInstant()
    else -> Instant.from(t)
}

/** The zone a DTSTART was given in, if Android knows it; UTC for UTC times, [floating] for floating ones. */
private fun zoneOf(start: DtStart<Temporal>, floating: ZoneId): String = when (val t = start.date) {
    // ical4j maps TZIDs to its own zone ids; the provider needs the Olson id, which is usually the TZID itself.
    is ZonedDateTime -> listOfNotNull(start.getParameter<TzId>(Parameter.TZID).getOrNull()?.value, t.zone.id)
        .firstOrNull { runCatching { ZoneId.of(it) }.isSuccess } ?: "UTC"
    is LocalDateTime -> floating.id
    else -> "UTC"
}

/**
 * The iCalendar resource for a series: [master] and its [exceptions] as provider rows. Properties the rows don't hold
 * (attendees, categories, …) are carried over from [stored], the resource as last downloaded or uploaded.
 */
fun toICal(master: EventRow, exceptions: List<EventRow>, stored: String?): String {
    val old = stored?.let { runCatching { CalendarBuilder().build(StringReader(it)).getComponents<VEvent>(Component.VEVENT) }.getOrNull() }.orEmpty()
    val uid = master.string(Events.UID_2445)!!
    val masterAllDay = master.long(Events.ALL_DAY) == 1L
    val masterZone = zone(master)
    val zones = mutableSetOf<String>()
    val cal = Calendar()
    cal.add<Calendar>(ProdId("-//bornagojsic//Calendar//EN"))
    cal.add<Calendar>(ImmutableVersion.VERSION_2_0)

    fun event(row: EventRow, originalTime: Long?): VEvent {
        val allDay = row.long(Events.ALL_DAY) == 1L
        val zone = zone(row)
        fun temporal(millis: Long, allDay: Boolean, zone: ZoneId): Temporal = Instant.ofEpochMilli(millis).let {
            when {
                allDay -> it.atZone(ZoneOffset.UTC).toLocalDate()
                zone.normalized() == ZoneOffset.UTC -> it
                else -> it.atZone(zone).also { _ -> zones += zone.id }
            }
        }
        val e = VEvent(false)
        val begin = row.long(Events.DTSTART)!!
        e.add<VEvent>(Uid(uid))
        e.add<VEvent>(DtStamp(Instant.now()))
        e.add<VEvent>(DtStart(temporal(begin, allDay, zone)))
        if (originalTime != null) e.add<VEvent>(RecurrenceId(temporal(originalTime, masterAllDay, masterZone)))
        val dtend = row.long(Events.DTEND)
        val duration = row.string(Events.DURATION)
        if (dtend != null) e.add<VEvent>(DtEnd(temporal(dtend, allDay, zone)))
        else if (duration != null) e.add<VEvent>(net.fortuna.ical4j.model.property.Duration(amount(duration)))
        row.string(Events.RRULE)?.let { e.add<VEvent>(RRule<Temporal>(it)) }
        row.string(Events.RDATE)?.let { e.add<VEvent>(RDate<Temporal>(dateParams(allDay), utcDates(it))) }
        // Cancelled exceptions become EXDATEs on the master, which every client understands.
        val cancelled = exceptions.filter { it.long(Events.STATUS) == Events.STATUS_CANCELED.toLong() }
            .map { exdate(Instant.ofEpochMilli(it.long(Events.ORIGINAL_INSTANCE_TIME)!!), allDay) }
        val exdates = listOfNotNull(row.string(Events.EXDATE)?.let(::utcDates)?.takeIf { it.isNotEmpty() }) +
            cancelled.takeIf { originalTime == null }.orEmpty()
        if (exdates.isNotEmpty()) e.add<VEvent>(ExDate<Temporal>(dateParams(allDay), exdates.joinToString(",")))
        row.string(Events.TITLE)?.takeIf { it.isNotEmpty() }?.let { e.add<VEvent>(Summary(it)) }
        row.string(Events.EVENT_LOCATION)?.takeIf { it.isNotEmpty() }?.let { e.add<VEvent>(Location(it)) }
        row.string(Events.DESCRIPTION)?.takeIf { it.isNotEmpty() }?.let { e.add<VEvent>(Description(it)) }
        when (row.long(Events.STATUS)?.toInt()) {
            Events.STATUS_CONFIRMED -> e.add<VEvent>(Status(Status.VALUE_CONFIRMED))
            Events.STATUS_TENTATIVE -> e.add<VEvent>(Status(Status.VALUE_TENTATIVE))
        }
        val previous = old.firstOrNull { o ->
            val rid = o.recurrenceId?.date
            if (originalTime == null) rid == null else rid != null && instant(rid, ZoneId.systemDefault()).toEpochMilli() == originalTime
        }
        previous?.propertyList?.all?.filter { it.name !in MAPPED }?.forEach { e.add<VEvent>(it) }
        row.reminders.forEach { m ->
            e.add<VEvent>(VAlarm(Duration.ofMinutes(-m.toLong())).apply {
                add<VAlarm>(Action(Action.VALUE_DISPLAY))
                add<VAlarm>(Description(row.string(Events.TITLE).orEmpty().ifEmpty { "Reminder" }))
            })
        }
        return e
    }

    cal.add<Calendar>(event(master, null))
    exceptions.filter { it.long(Events.STATUS) != Events.STATUS_CANCELED.toLong() }
        .forEach { cal.add<Calendar>(event(it, it.long(Events.ORIGINAL_INSTANCE_TIME)!!)) }
    val registry = TimeZoneRegistryFactory.getInstance().createRegistry()
    zones.forEach { id -> registry.getTimeZone(id)?.vTimeZone?.let { cal.add<Calendar>(it) } }
    return cal.toString()
}

/** The provider's DURATION ("P3600S", "P1D", or RFC 5545) as a java.time amount; ical4j rejects "P3600S". */
private fun amount(duration: String): TemporalAmount =
    Regex("P(\\d+)S").matchEntire(duration)?.let { Duration.ofSeconds(it.groupValues[1].toLong()) }
        ?: runCatching { Duration.parse(duration) }.getOrNull()
        ?: Period.parse(duration)

private fun zone(row: EventRow): ZoneId =
    row.string(Events.EVENT_TIMEZONE)?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC

private fun dateParams(allDay: Boolean) = if (allDay) ParameterList(listOf(Value.DATE)) else ParameterList()

/**
 * A provider EXDATE/RDATE value as UTC (or DATE) values. Other sync adapters may write "TZID;local,local";
 * those local times are converted to UTC.
 */
private fun utcDates(raw: String): String {
    val zone = raw.substringBefore(';', "").takeIf { it.isNotEmpty() }?.let { runCatching { ZoneId.of(it) }.getOrNull() }
    return raw.substringAfter(';').split(',').filter { it.isNotBlank() }.joinToString(",") {
        if (zone == null || it.length == 8 || it.endsWith("Z")) it
        else LocalDateTime.parse(it, java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
            .atZone(zone).withZoneSameInstant(ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
    }
}
