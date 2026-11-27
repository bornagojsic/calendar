package dev.bornagojsic.calendar.domain

import org.junit.Assert.assertEquals
import org.junit.Test
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
        val e = timed(1, "2026-11-26T20:00", "2026-11-28T02:00")
        assertEquals(listOf(Placed(e, 0, 1440, 0, 1)), layoutDay(listOf(e), day, zone))
        assertEquals(listOf(Placed(e, 0, 120, 0, 1)), layoutDay(listOf(e), day.plusDays(1), zone))
    }

    @Test fun shortEventGetsMinimumHeight() {
        val e = timed(1, "2026-11-27T09:00", "2026-11-27T09:00")
        assertEquals(listOf(Placed(e, 540, 540 + MIN_BLOCK_MINUTES, 0, 1)), layoutDay(listOf(e), day, zone))
    }

    @Test fun weekStartFollowsLocale() {
        assertEquals(LocalDate.of(2026, 11, 23), weekStart(day, Locale.UK)) // Monday
        assertEquals(LocalDate.of(2026, 11, 22), weekStart(day, Locale.US)) // Sunday
    }
}
