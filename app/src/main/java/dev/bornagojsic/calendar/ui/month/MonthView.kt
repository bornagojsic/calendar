package dev.bornagojsic.calendar.ui.month

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bornagojsic.calendar.domain.Event
import dev.bornagojsic.calendar.domain.layoutBars
import dev.bornagojsic.calendar.domain.weekStart
import dev.bornagojsic.calendar.ui.firstDayOfWeek
import dev.bornagojsic.calendar.ui.DatePager
import dev.bornagojsic.calendar.ui.EventChip
import dev.bornagojsic.calendar.ui.chipPitch
import dev.bornagojsic.calendar.ui.EventLoader
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import androidx.compose.ui.platform.LocalConfiguration

private val BASE = YearMonth.of(2000, 1)
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
    val pitch = chipPitch()
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
            val days = (0L until 7).map { first.plusDays(w * 7 + it) }
            val bars = remember(events, first, zone) { layoutBars(events, days, zone) }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                val width = maxWidth / 7
                val fits = ((maxHeight - DAY_NUMBER_HEIGHT) / pitch).toInt().coerceAtLeast(0)
                // Keep the last slot for "+k" when not everything fits.
                val shown = if (bars.all { it.slot < fits }) fits else (fits - 1).coerceAtLeast(0)
                Row(Modifier.fillMaxSize()) {
                    days.forEach { day ->
                        MonthCell(
                            day = day,
                            inMonth = YearMonth.from(day) == month,
                            isToday = day == today,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .border(0.5.dp, border)
                                .clickable { onDay(day) },
                        )
                    }
                }
                bars.filter { it.slot < shown }.forEach { b ->
                    EventChip(
                        b.event,
                        Modifier
                            .offset(x = width * b.startCol, y = DAY_NUMBER_HEIGHT + pitch * b.slot)
                            .width(width * (b.endCol - b.startCol + 1))
                            .padding(horizontal = 2.dp),
                    )
                }
                for (c in days.indices) {
                    val hidden = bars.count { it.slot >= shown && c in it.startCol..it.endCol }
                    if (hidden > 0) {
                        Text(
                            "+$hidden",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // Pulled up into the row when it is too short for any chip, so the count stays visible.
                            modifier = Modifier.offset(x = width * c, y = minOf(DAY_NUMBER_HEIGHT + pitch * shown, maxHeight - pitch).coerceAtLeast(0.dp)).padding(start = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthCell(day: LocalDate, inMonth: Boolean, isToday: Boolean, modifier: Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier.padding(2.dp), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
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
    }
}
