package dev.bornagojsic.calendar.ui.month

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bornagojsic.calendar.domain.Event
import dev.bornagojsic.calendar.domain.weekStart
import dev.bornagojsic.calendar.ui.firstDayOfWeek
import dev.bornagojsic.calendar.ui.DatePager
import dev.bornagojsic.calendar.ui.EventChip
import dev.bornagojsic.calendar.ui.EventLoader
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import androidx.compose.ui.platform.LocalConfiguration

private val BASE = YearMonth.of(2000, 1)
private val CHIP_HEIGHT = 18.dp
private val DAY_NUMBER_HEIGHT = 26.dp

/** 6x7 month grid. Tapping a day calls [onDay]. */
@Composable
fun MonthView(date: LocalDate, onDate: (LocalDate) -> Unit, onDay: (LocalDate) -> Unit, load: EventLoader) {
    DatePager(
        date = date,
        onDate = onDate,
        offsetOf = { ChronoUnit.MONTHS.between(BASE, YearMonth.from(it)).toInt() },
        rangeOf = { BASE.plusMonths(it.toLong()).let { m -> m.atDay(1)..m.atEndOfMonth() } },
        modifier = Modifier.fillMaxSize(),
    ) { range ->
        val first = weekStart(range.start, firstDayOfWeek())
        val events by remember(range) { load(first, first.plusDays(41)) }.collectAsStateWithLifecycle(emptyList())
        MonthPage(YearMonth.from(range.start), first, events, onDay)
    }
}

@Composable
private fun MonthPage(month: YearMonth, first: LocalDate, events: List<Event>, onDay: (LocalDate) -> Unit) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val border = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(vertical = 4.dp)) {
            for (d in 0L until 7) {
                Text(
                    first.plusDays(d).dayOfWeek.getDisplayName(TextStyle.NARROW, LocalConfiguration.current.locales[0]),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        for (w in 0L until 6) {
            Row(Modifier.weight(1f)) {
                for (d in 0L until 7) {
                    val day = first.plusDays(w * 7 + d)
                    MonthCell(
                        day = day,
                        inMonth = YearMonth.from(day) == month,
                        isToday = day == today,
                        events = events.filter { day in it.days(zone) }.sortedWith(compareBy({ !it.allDay }, { it.begin })),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .border(0.5.dp, border)
                            .clickable { onDay(day) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthCell(day: LocalDate, inMonth: Boolean, isToday: Boolean, events: List<Event>, modifier: Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    BoxWithConstraints(modifier.padding(2.dp)) {
        val fits = ((maxHeight - DAY_NUMBER_HEIGHT) / CHIP_HEIGHT).toInt().coerceAtLeast(0)
        // Keep the last slot for "+k" when not everything fits.
        val shown = if (events.size <= fits) events.size else (fits - 1).coerceAtLeast(0)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(22.dp)
                    .background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    day.dayOfMonth.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = when {
                        isToday -> MaterialTheme.colorScheme.onPrimary
                        inMonth -> MaterialTheme.colorScheme.onSurface
                        else -> muted.copy(alpha = 0.5f)
                    },
                )
            }
            events.take(shown).forEach { EventChip(it) }
            if (shown < events.size) {
                Text(
                    "+${events.size - shown}",
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}
