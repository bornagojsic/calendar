package dev.bornagojsic.calendar.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class EventTest {
    private val zone = ZoneId.of("Europe/Zagreb")
    private val day = LocalDate.of(2026, 11, 27)

    private fun timed(id: Long, from: String, to: String) = Event(
        id, id, "e$id",
        LocalDateTime.parse(from).atZone(zone).toInstant(),
        LocalDateTime.parse(to).atZone(zone).toInstant(),
        allDay = false, color = 0,
    )

    @Test fun allDayEventUsesUtcDatesAndExclusiveEnd() {
        val e = Event(1, 1, "", day.atStartOfDay(ZoneOffset.UTC).toInstant(),
            day.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant(), allDay = true, color = 0)
        // Zone far west of UTC must not shift the dates.
        assertEquals(day..day.plusDays(1), e.days(ZoneId.of("America/Los_Angeles")))
    }

    @Test fun timedEventEndingAtMidnightStaysOnOneDay() {
        assertEquals(day..day, timed(1, "2026-11-27T22:00", "2026-11-28T00:00").days(zone))
        assertEquals(day..day.plusDays(1), timed(1, "2026-11-27T22:00", "2026-11-28T01:00").days(zone))
    }

    @Test fun overlappingEventsSplitIntoColumns() {
        val a = timed(1, "2026-11-27T09:00", "2026-11-27T11:00")
        val b = timed(2, "2026-11-27T10:00", "2026-11-27T12:00")
        val c = timed(3, "2026-11-27T11:00", "2026-11-27T12:00") // reuses a's column
        val d = timed(4, "2026-11-27T13:00", "2026-11-27T14:00") // separate cluster
        val placed = layoutDay(listOf(d, c, b, a), day, zone).associateBy { it.event.id }
        assertEquals(Placed(a, 540, 660, 0, 2), placed[1])
        assertEquals(Placed(b, 600, 720, 1, 2), placed[2])
        assertEquals(Placed(c, 660, 720, 0, 2), placed[3])
        assertEquals(Placed(d, 780, 840, 0, 1), placed[4])
    }

    @Test fun multiDayEventIsClippedToTheDay() {
        val e = timed(1, "2026-11-27T20:00", "2026-11-28T02:00")
        assertEquals(listOf(Placed(e, 1200, 1440, 0, 1)), layoutDay(listOf(e), day, zone))
        assertEquals(listOf(Placed(e, 0, 120, 0, 1)), layoutDay(listOf(e), day.plusDays(1), zone))
    }

    @Test fun shortEventGetsMinimumHeight() {
        val e = timed(1, "2026-11-27T09:00", "2026-11-27T09:00")
        assertEquals(listOf(Placed(e, 540, 540 + MIN_BLOCK_MINUTES, 0, 1)), layoutDay(listOf(e), day, zone))
    }

    private fun allDay(id: Long, from: LocalDate, days: Long) = Event(
        id, id, "e$id", from.atStartOfDay(ZoneOffset.UTC).toInstant(),
        from.plusDays(days).atStartOfDay(ZoneOffset.UTC).toInstant(), allDay = true, color = 0,
    )

    @Test fun longTimedEventsLeaveTheGrid() {
        val long = timed(1, "2026-11-27T09:00", "2026-11-28T09:00")
        val night = timed(2, "2026-11-27T22:00", "2026-11-28T02:00")
        assertEquals(true, long.isBar)
        assertEquals(false, night.isBar)
        assertEquals(listOf(night), layoutDay(listOf(long, night), day, zone).map { it.event })
    }

    @Test fun barsKeepTheirSlotAndAreCutAtTheRowEdges() {
        val week = (0L until 7).map { day.minusDays(4).plusDays(it) } // Mon 23 .. Sun 29
        val a = allDay(1, day.minusDays(6), 4) // Sat 21 .. Tue 24: cut at the start
        val b = allDay(2, day.minusDays(3), 3) // Tue 24 .. Thu 26
        val c = timed(3, "2026-11-26T10:00", "2026-12-01T10:00") // Thu 26 .. Tue 1: cut at the end
        val chip = timed(4, "2026-11-23T09:00", "2026-11-23T10:00") // Mon, single-day
        val night = timed(5, "2026-11-25T22:00", "2026-11-26T02:00") // Wed and Thu chips
        val bars = layoutBars(listOf(chip, night, c, b, a), week, zone)
        assertEquals(
            listOf(
                Bar(a, 0, 0, 1),
                Bar(b, 1, 1, 3),
                Bar(c, 0, 3, 6),
                Bar(chip, 1, 0, 0),
                Bar(night, 0, 2, 2),
                Bar(night, 2, 3, 3),
            ),
            bars,
        )
    }

    @Test fun weekStartFollowsLocaleUnlessOverridden() {
        assertEquals(LocalDate.of(2026, 11, 23), weekStart(day, Prefs().firstDayOfWeek(Locale.UK))) // Monday
        assertEquals(LocalDate.of(2026, 11, 22), weekStart(day, Prefs().firstDayOfWeek(Locale.US))) // Sunday
        assertEquals(LocalDate.of(2026, 11, 21), weekStart(day, Prefs(weekStart = DayOfWeek.SATURDAY).firstDayOfWeek(Locale.UK)))
        assertEquals(day, weekStart(day, day.dayOfWeek))
    }
}
