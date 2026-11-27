package dev.bornagojsic.calendar.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class RecurrenceTest {
    private val zone = ZoneId.of("Europe/Zagreb")
    private val start = LocalDate.of(2026, 11, 27) // a Friday

    @Test
    fun formatsAndParsesBack() {
        val rule = Rule(Freq.WEEKLY, 2, setOf(WEDNESDAY, MONDAY), until = LocalDate.of(2027, 3, 3))
        // Timed: UNTIL is the end of that day in the event zone, in UTC (CET is UTC+1).
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;UNTIL=20270303T225959Z", rule.format(zone, allDay = false))
        assertEquals(rule, Rule.parse(rule.format(zone, false), zone))
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;UNTIL=20270303", rule.format(zone, allDay = true))
        assertEquals(rule, Rule.parse(rule.format(zone, true), zone))
        assertEquals(Rule(Freq.DAILY, count = 5), Rule.parse("RRULE:FREQ=DAILY;COUNT=5", zone))
    }

    @Test
    fun richerRulesDontParse() {
        assertNull(Rule.parse("FREQ=MONTHLY;BYDAY=2TU", zone))
        assertNull(Rule.parse("FREQ=MONTHLY;BYMONTHDAY=1,15", zone))
        assertNull(Rule.parse("FREQ=HOURLY", zone))
    }

    @Test
    fun describes() {
        val l = Locale.UK
        assertEquals("Daily", Rule(Freq.DAILY).describe(start, l))
        assertEquals("Weekly on Friday", Rule(Freq.WEEKLY).describe(start, l))
        assertEquals("Every weekday", Rule.presets(start).last().describe(start, l))
        assertEquals("Monthly on day 27", Rule(Freq.MONTHLY).describe(start, l))
        assertEquals("Annually on 27 November", Rule(Freq.YEARLY).describe(start, l))
        assertEquals("Every 2 weeks on Monday, Wednesday; 4 times", Rule(Freq.WEEKLY, 2, setOf(WEDNESDAY, MONDAY), count = 4).describe(start, l))
        assertEquals("Every 3 days; until 3 Mar 2027", Rule(Freq.DAILY, 3, until = LocalDate.of(2027, 3, 3)).describe(start, l))
    }

    @Test
    fun cutsSeries() {
        assertEquals("FREQ=MONTHLY;BYDAY=2TU;UNTIL=20261127T085959Z", withUntil("FREQ=MONTHLY;BYDAY=2TU;COUNT=10", "20261127T085959Z"))
        val at = Instant.parse("2026-11-27T09:00:00Z")
        assertEquals("20261127T085959Z", untilBefore(at, allDay = false))
        assertEquals("20261126", untilBefore(Instant.parse("2026-11-27T00:00:00Z"), allDay = true))
    }

    @Test
    fun shiftsExdates() {
        assertEquals(
            "20261223T180000Z,20261230,Europe/Zagreb;20261229T190000",
            shiftExdate("20261222T170000Z,20261229,Europe/Zagreb;20261229T190000", java.time.Duration.ofDays(1).plusHours(1), zone),
        )
        // A week of wall-clock time across the 29 Mar DST change: 09:00 CET (08:00Z) to 09:00 CEST (07:00Z).
        assertEquals("20260330T070000Z", shiftExdate("20260323T080000Z", java.time.Duration.ofDays(7), zone))
        assertEquals("20261222T170000Z", exdate(Instant.parse("2026-12-22T17:00:00Z"), allDay = false))
    }

    @Test
    fun keepsWhatTheEditorDoesntTouch() {
        val wk = "FREQ=WEEKLY;INTERVAL=2;BYDAY=TU,SU;WKST=SU"
        assertEquals(wk, Rule.parse(wk, zone)!!.format(zone, false))
        val d = Draft(
            1, start = LocalDateTime.of(2026, 11, 27, 10, 0), end = LocalDateTime.of(2026, 11, 27, 11, 0), zone = zone,
            rrule = "FREQ=DAILY;UNTIL=20261129T090000Z",
        )
        // A zone change keeps an exact UNTIL; only an all-day switch rewrites it.
        assertEquals(d.rrule, d.reshape(zone = ZoneId.of("UTC")).rrule)
        assertEquals("FREQ=DAILY;UNTIL=20261129", d.reshape(allDay = true).rrule)
        assertEquals(
            "FREQ=WEEKLY;BYDAY=MO;UNTIL=20261229T090000Z",
            d.copy(rrule = "FREQ=WEEKLY;BYDAY=FR;UNTIL=20261229T090000Z").moveStart(LocalDateTime.of(2026, 11, 30, 10, 0)).rrule,
        )
    }

    @Test
    fun keepsLaterExdates() {
        val at = Instant.parse("2026-12-08T17:00:00Z")
        assertEquals("20261208T170000Z,20261222T170000Z", exdatesFrom("20261201T170000Z,20261208T170000Z,20261222T170000Z", at, false))
        assertNull(exdatesFrom("20261201T170000Z", at, false))
    }

    @Test
    fun dstGapStartIsInvalidWhenEndResolvesEarlier() {
        // 02:30 doesn't exist on 29 Mar 2026 in Zagreb and resolves to 03:30, after the 03:00 end.
        val d = Draft(1, start = LocalDateTime.of(2026, 3, 29, 2, 30), end = LocalDateTime.of(2026, 3, 29, 3, 0), zone = zone)
        assertFalse(d.valid)
    }

    @Test
    fun draftTimes() {
        val d = Draft(1, start = LocalDateTime.of(2026, 11, 27, 10, 0), end = LocalDateTime.of(2026, 11, 27, 11, 30), zone = zone)
        assertEquals(Instant.parse("2026-11-27T09:00:00Z"), d.begin())
        assertEquals("P5400S", d.duration())
        val allDay = d.copy(allDay = true, end = LocalDateTime.of(2026, 11, 28, 0, 0))
        assertEquals(Instant.parse("2026-11-27T00:00:00Z"), allDay.begin())
        assertEquals(Instant.parse("2026-11-29T00:00:00Z"), allDay.endInstant())
        assertEquals("P2D", allDay.duration())
        assertFalse(d.copy(end = d.start.minusMinutes(1)).valid)
    }

    @Test
    fun moveStartKeepsLengthAndWeekday() {
        val d = Draft(
            1, start = LocalDateTime.of(2026, 11, 27, 10, 0), end = LocalDateTime.of(2026, 11, 27, 11, 0), zone = zone,
            rrule = Rule(Freq.WEEKLY, days = setOf(FRIDAY)).format(zone, false),
        )
        val moved = d.moveStart(LocalDateTime.of(2026, 11, 30, 9, 0)) // Monday
        assertEquals(LocalDateTime.of(2026, 11, 30, 10, 0), moved.end)
        assertEquals("FREQ=WEEKLY;BYDAY=MO", moved.rrule)
    }
}
