package dev.bornagojsic.calendar.sync

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ICalTest {
    private val zagreb = ZoneId.of("Europe/Zagreb")
    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zagreb).toInstant().toEpochMilli()

    private val series = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//test//EN
        BEGIN:VEVENT
        UID:standup@example.com
        DTSTAMP:20261101T000000Z
        DTSTART;TZID=Europe/Zagreb:20261102T090000
        DTEND;TZID=Europe/Zagreb:20261102T093000
        RRULE:FREQ=WEEKLY;BYDAY=MO
        EXDATE;TZID=Europe/Zagreb:20261116T090000
        SUMMARY:Standup
        LOCATION:Room 1
        ATTENDEE;CN=Ana:mailto:ana@example.com
        BEGIN:VALARM
        ACTION:DISPLAY
        DESCRIPTION:Standup
        TRIGGER:-PT10M
        END:VALARM
        END:VEVENT
        BEGIN:VEVENT
        UID:standup@example.com
        DTSTAMP:20261101T000000Z
        RECURRENCE-ID;TZID=Europe/Zagreb:20261109T090000
        DTSTART;TZID=Europe/Zagreb:20261109T110000
        DTEND;TZID=Europe/Zagreb:20261109T120000
        SUMMARY:Standup (moved)
        END:VEVENT
        END:VCALENDAR
    """.trimIndent().replace("\n", "\r\n") + "\r\n"

    @Test
    fun parsesRecurringEventWithException() {
        val (master, exception) = parseEvents(series).also { assertEquals(2, it.size) }

        assertEquals("standup@example.com", master[Events.UID_2445])
        assertEquals("Standup", master[Events.TITLE])
        assertEquals("Room 1", master[Events.EVENT_LOCATION])
        assertEquals(millis(2026, 11, 2, 9), master[Events.DTSTART])
        assertEquals("Europe/Zagreb", master[Events.EVENT_TIMEZONE])
        assertEquals(0, master[Events.ALL_DAY])
        assertEquals("FREQ=WEEKLY;BYDAY=MO", master[Events.RRULE])
        // Recurring rows carry DURATION, not DTEND; EXDATEs are UTC (CET is UTC+1).
        assertNull(master[Events.DTEND])
        assertEquals("P1800S", master[Events.DURATION])
        assertEquals("20261116T080000Z", master[Events.EXDATE])
        assertEquals(listOf(10), master.reminders)
        assertEquals("Ana", master.attendees.single()[Attendees.ATTENDEE_NAME])
        assertEquals("ana@example.com", master.attendees.single()[Attendees.ATTENDEE_EMAIL])

        assertEquals("Standup (moved)", exception[Events.TITLE])
        assertEquals(millis(2026, 11, 9, 9), exception[Events.ORIGINAL_INSTANCE_TIME])
        assertEquals(millis(2026, 11, 9, 11), exception[Events.DTSTART])
        assertEquals(millis(2026, 11, 9, 12), exception[Events.DTEND])
        assertNull(exception[Events.RRULE])
        assertEquals(emptyList<Int>(), exception.reminders)
    }

    @Test
    fun writesRowsBackWithExceptionsAndUnknownProperties() {
        val (master, moved) = parseEvents(series)
        val cancelled = EventRow(mapOf(
            Events.DTSTART to millis(2026, 11, 23, 9),
            Events.DTEND to millis(2026, 11, 23, 9, 30),
            Events.EVENT_TIMEZONE to "Europe/Zagreb",
            Events.ORIGINAL_INSTANCE_TIME to millis(2026, 11, 23, 9),
            Events.STATUS to Events.STATUS_CANCELED,
        ))
        val ics = toICal(master.copy(reminders = listOf(10, 30)), listOf(moved, cancelled), series)

        assertTrue(ics, "ATTENDEE;CN=Ana:mailto:ana@example.com" in ics)
        assertTrue(ics, "BEGIN:VTIMEZONE" in ics)
        val (m, e) = parseEvents(ics).also { assertEquals(2, it.size) }
        assertEquals(master.values - Events.EXDATE - Events.HAS_ALARM, m.values - Events.EXDATE - Events.HAS_ALARM)
        // The cancelled occurrence is an EXDATE, next to the one already there.
        assertEquals(setOf("20261116T080000Z", "20261123T080000Z"), m.string(Events.EXDATE)!!.split(',').toSet())
        assertEquals(listOf(10, 30), m.reminders)
        assertEquals(moved.values - Events.UID_2445, e.values - Events.UID_2445)
    }

    @Test
    fun allDayEventsAreUtcMidnights() {
        val ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//test//EN
            BEGIN:VEVENT
            UID:trip
            DTSTAMP:20261101T000000Z
            DTSTART;VALUE=DATE:20261224
            DTEND;VALUE=DATE:20261227
            SUMMARY:Trip
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n") + "\r\n"
        val row = parseEvents(ics).single()
        assertEquals(1, row[Events.ALL_DAY])
        assertEquals("UTC", row[Events.EVENT_TIMEZONE])
        assertEquals(1798070400000L, row[Events.DTSTART]) // 2026-12-24T00:00Z
        assertEquals(1798070400000L + 3 * 86_400_000L, row[Events.DTEND])
        assertTrue(toICal(row, emptyList(), null).contains("DTSTART;VALUE=DATE:20261224"))
    }

    @Test
    fun exruleRoundTrips() {
        val ics = series.replace("RRULE:FREQ=WEEKLY;BYDAY=MO\r\n", "RRULE:FREQ=WEEKLY;BYDAY=MO\r\nEXRULE:FREQ=MONTHLY;BYDAY=1MO\r\n")
        val master = parseEvents(ics).first()
        assertEquals("FREQ=MONTHLY;BYDAY=1MO", master[Events.EXRULE])
        assertEquals("FREQ=MONTHLY;BYDAY=1MO", parseEvents(toICal(master, emptyList(), ics)).first()[Events.EXRULE])
    }
}
