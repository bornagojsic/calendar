package dev.bornagojsic.calendar.reminders

import org.junit.Assert.assertEquals
import org.junit.Test

class SnoozeLabelTest {
    @Test
    fun label() {
        assertEquals("Snooze 15m", snoozeLabel(15))
        assertEquals("Snooze 1h", snoozeLabel(60))
        assertEquals("Snooze 2h", snoozeLabel(120))
    }
}
