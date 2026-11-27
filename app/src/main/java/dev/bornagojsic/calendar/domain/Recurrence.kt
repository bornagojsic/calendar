package dev.bornagojsic.calendar.domain

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

enum class Freq(val unit: String) { DAILY("day"), WEEKLY("week"), MONTHLY("month"), YEARLY("year") }

private val CODES = mapOf(
    "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY,
    "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY, "SU" to DayOfWeek.SUNDAY,
)
private val WEEKDAYS = DayOfWeek.entries.take(5).toSet()
private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
private val UTC_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

/**
 * The subset of RRULE the editor can build: FREQ, INTERVAL, plain BYDAY, and one end (UNTIL date or COUNT).
 * Anything richer (BYMONTHDAY, BYSETPOS, "2TU", ...) parses to null and is kept as the raw string.
 */
data class Rule(
    val freq: Freq,
    val interval: Int = 1,
    val days: Set<DayOfWeek> = emptySet(),
    val until: LocalDate? = null,
    val count: Int? = null,
) {
    /** RRULE text. UNTIL must match DTSTART's type: a date for all-day events, else the end of that day in [zone] as UTC. */
    fun format(zone: ZoneId, allDay: Boolean): String = buildList {
        add("FREQ=${freq.name}")
        if (interval > 1) add("INTERVAL=$interval")
        if (days.isNotEmpty()) add("BYDAY=" + days.sorted().joinToString(",") { d -> CODES.entries.first { it.value == d }.key })
        until?.let {
            add("UNTIL=" + if (allDay) it.format(DATE) else it.atTime(23, 59, 59).atZone(zone).withZoneSameInstant(ZoneOffset.UTC).format(UTC_TIME))
        }
        count?.let { add("COUNT=$it") }
    }.joinToString(";")

    /** "Every 2 weeks on Monday, Wednesday; until 3 Mar 2027". [start] supplies the implied day for monthly/yearly. */
    fun describe(start: LocalDate, locale: Locale = Locale.getDefault()): String {
        val base = when {
            freq == Freq.WEEKLY && interval == 1 && days == WEEKDAYS -> "Every weekday"
            interval == 1 -> when (freq) {
                Freq.DAILY -> "Daily"
                Freq.WEEKLY -> "Weekly"
                Freq.MONTHLY -> "Monthly"
                Freq.YEARLY -> "Annually"
            }
            else -> "Every $interval ${freq.unit}s"
        }
        val on = when {
            base == "Every weekday" -> ""
            freq == Freq.WEEKLY -> " on " + days.ifEmpty { setOf(start.dayOfWeek) }.sorted()
                .joinToString(", ") { it.getDisplayName(TextStyle.FULL, locale) }
            freq == Freq.MONTHLY -> " on day ${start.dayOfMonth}"
            freq == Freq.YEARLY -> " on " + start.format(DateTimeFormatter.ofPattern("d MMMM", locale))
            else -> ""
        }
        val end = when {
            until != null -> "; until " + until.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
            count != null -> "; $count times"
            else -> ""
        }
        return base + on + end
    }

    companion object {
        /** Null when [rrule] uses parts the editor can't represent. Timed UNTILs are read back in [zone]. */
        fun parse(rrule: String, zone: ZoneId): Rule? {
            val parts = rrule.removePrefix("RRULE:").split(';').filter { it.isNotBlank() }.associate {
                val (k, v) = it.split('=', limit = 2).let { p -> p[0].uppercase() to p.getOrElse(1) { "" } }
                k to v
            }
            if (!parts.keys.all { it in setOf("FREQ", "INTERVAL", "BYDAY", "UNTIL", "COUNT", "WKST") }) return null
            val freq = Freq.entries.find { it.name == parts["FREQ"] } ?: return null
            val days = parts["BYDAY"]?.split(',')?.map { CODES[it] ?: return null }?.toSet() ?: emptySet()
            if (days.isNotEmpty() && freq != Freq.WEEKLY) return null
            val until = parts["UNTIL"]?.let {
                runCatching {
                    if (it.length == 8) LocalDate.parse(it, DATE)
                    else LocalDateTime.parse(it.removeSuffix("Z"), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                        .atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDate()
                }.getOrNull() ?: return null
            }
            return Rule(
                freq = freq,
                interval = parts["INTERVAL"]?.toIntOrNull() ?: 1,
                days = days,
                until = until,
                count = parts["COUNT"]?.toIntOrNull(),
            )
        }

        /** Google Calendar's presets for an event starting on [start]. */
        fun presets(start: LocalDate) = listOf(
            Rule(Freq.DAILY),
            Rule(Freq.WEEKLY, days = setOf(start.dayOfWeek)),
            Rule(Freq.MONTHLY),
            Rule(Freq.YEARLY),
            Rule(Freq.WEEKLY, days = WEEKDAYS),
        )
    }
}

/** [rrule] ending with [until] in place of any UNTIL or COUNT; works on rules [Rule.parse] can't read. */
fun withUntil(rrule: String, until: String): String =
    (rrule.split(';').filterNot { it.isBlank() || it.startsWith("UNTIL=") || it.startsWith("COUNT=") } + "UNTIL=$until").joinToString(";")

/** UNTIL value that ends a series just before the occurrence at [instance]. */
fun untilBefore(instance: Instant, allDay: Boolean): String =
    if (allDay) instance.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1).format(DATE)
    else instance.minusSeconds(1).atZone(ZoneOffset.UTC).format(UTC_TIME)

/** EXDATE value for the occurrence at [instance], typed like DTSTART. */
fun exdate(instance: Instant, allDay: Boolean): String =
    if (allDay) instance.atZone(ZoneOffset.UTC).toLocalDate().format(DATE) else instance.atZone(ZoneOffset.UTC).format(UTC_TIME)

/**
 * Moves each EXDATE in [exdate] by [by], so occurrences deleted from a series stay deleted when the series moves.
 * shortcut: only UTC ("...Z") and date-only values are shifted; TZID-local values are kept as they are.
 */
fun shiftExdate(exdate: String, by: Duration): String = exdate.split(',').joinToString(",") {
    when {
        it.endsWith("Z") -> runCatching {
            LocalDateTime.parse(it.removeSuffix("Z"), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                .plus(by).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")) + "Z"
        }.getOrDefault(it)
        it.length == 8 -> runCatching { LocalDate.parse(it, DATE).plusDays(by.toDays()).format(DATE) }.getOrDefault(it)
        else -> it
    }
}
