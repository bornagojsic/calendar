package dev.bornagojsic.calendar.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class PrefsTest {
    private val prefs = Prefs(timedReminders = listOf(10), allDayReminders = listOf(1_440))
    private val timed = Draft(calendarId = 1, start = LocalDateTime.parse("2026-11-27T10:00"), end = LocalDateTime.parse("2026-11-27T11:00"), reminders = listOf(10))

    @Test
    fun allDaySwitchSwapsUntouchedDefaults() {
        val allDay = prefs.followDefaults(timed, timed.reshape(allDay = true), edited = false)
        assertEquals(listOf(1_440), allDay.reminders)
        assertEquals(listOf(10), prefs.followDefaults(allDay, allDay.reshape(allDay = false), edited = false).reminders)
    }

    @Test
    fun allDaySwitchKeepsEditedReminders() {
        // Edited to equal the all-day defaults: switching back must still keep them.
        val edited = timed.copy(reminders = listOf(1_440))
        val allDay = prefs.followDefaults(edited, edited.reshape(allDay = true), edited = true)
        assertEquals(listOf(1_440), prefs.followDefaults(allDay, allDay.reshape(allDay = false), edited = true).reminders)
    }

    @Test
    fun otherEditsKeepReminders() {
        assertEquals(listOf(10), prefs.followDefaults(timed, timed.copy(title = "x"), edited = false).reminders)
    }
}
