package dev.bornagojsic.calendar.ui.day

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bornagojsic.calendar.domain.Event
import dev.bornagojsic.calendar.domain.layoutBars
import dev.bornagojsic.calendar.domain.layoutDay
import dev.bornagojsic.calendar.domain.weekStart
import dev.bornagojsic.calendar.ui.firstDayOfWeek
import dev.bornagojsic.calendar.ui.DatePager
import dev.bornagojsic.calendar.ui.EventChip
import dev.bornagojsic.calendar.ui.EventLoader
import dev.bornagojsic.calendar.ui.eventColor
import dev.bornagojsic.calendar.ui.label
import dev.bornagojsic.calendar.ui.rememberHourFormat
import dev.bornagojsic.calendar.ui.rememberTimeFormat
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import androidx.compose.ui.platform.LocalConfiguration

private val GUTTER = 48.dp
private val BAR_PITCH = 20.dp

/** Day, 3-day and Week views: an all-day strip over a 24h grid, [days] columns per page. */
@Composable
fun TimeGridView(
    days: Int,
    date: LocalDate,
    onDate: (LocalDate) -> Unit,
    load: EventLoader,
    onEvent: (Event) -> Unit,
    onSlot: (LocalDateTime) -> Unit,
) {
    // Week pages start on the week's first day; Day and 3-day pages start on the date they opened on.
    val first = firstDayOfWeek()
    val base = LocalDate.ofEpochDay(rememberSaveable { (if (days == 7) weekStart(date, first) else date).toEpochDay() })
    var hourHeight by rememberSaveable { mutableFloatStateOf(48f) }
    val density = LocalDensity.current
    val scroll = rememberScrollState(with(density) { (hourHeight.dp * 7).roundToPx() }) // open at 7:00
    val now by produceState(LocalTime.now()) {
        while (true) {
            delay(60_000)
            value = LocalTime.now()
        }
    }
    DatePager(
        date = date,
        onDate = onDate,
        offsetOf = { Math.floorDiv(ChronoUnit.DAYS.between(base, it), days.toLong()).toInt() },
        rangeOf = { base.plusDays(it.toLong() * days).let { start -> start..start.plusDays(days - 1L) } },
        modifier = Modifier.fillMaxSize().pinchToZoom { zoom ->
            val next = (hourHeight * zoom).coerceIn(24f, 160f)
            // Scale the offset too, so the hour at the top stays put while zooming.
            scroll.dispatchRawDelta(scroll.value * (next / hourHeight - 1))
            hourHeight = next
        },
    ) { range ->
        val events by remember(range) { load(range.start, range.endInclusive) }.collectAsStateWithLifecycle(emptyList())
        TimeGridPage(
            days = (0 until days).map { range.start.plusDays(it.toLong()) },
            events = events,
            hourHeight = hourHeight.dp,
            scroll = scroll,
            now = now,
            onEvent = onEvent,
            onSlot = onSlot,
        )
    }
}

/** Two-finger pinch reports zoom; seen before the pager and scroll so they don't also move. */
private fun Modifier.pinchToZoom(onZoom: (Float) -> Unit) = this.then(
    Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    onZoom(event.calculateZoom())
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
)

@Composable
private fun TimeGridPage(
    days: List<LocalDate>,
    events: List<Event>,
    hourHeight: Dp,
    scroll: ScrollState,
    now: LocalTime,
    onEvent: (Event) -> Unit,
    onSlot: (LocalDateTime) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val border = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = GUTTER, top = 4.dp, bottom = 4.dp)) {
            Row { days.forEach { day -> Box(Modifier.weight(1f)) { DayLabel(day, day == today) } } }
            val bars = remember(events, days) { layoutBars(events.filter { it.isBar }, days, zone) }
            // Capped so a pile of all-day events can't squeeze out the time grid.
            BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max = 96.dp).verticalScroll(rememberScrollState())) {
                val width = maxWidth / days.size
                Box(Modifier.height(BAR_PITCH * (bars.maxOfOrNull { it.slot + 1 } ?: 0)))
                bars.forEach { b ->
                    EventChip(
                        b.event,
                        Modifier
                            .offset(x = width * b.startCol, y = BAR_PITCH * b.slot + 2.dp)
                            .width(width * (b.endCol - b.startCol + 1))
                            .padding(horizontal = 1.dp)
                            .clickable { onEvent(b.event) },
                    )
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.verticalScroll(scroll)) {
            HourLabels(hourHeight)
            days.forEach { day ->
                BoxWithConstraints(
                    Modifier
                        .weight(1f)
                        .height(hourHeight * 24)
                        // Tap an empty slot: new event at that hour. Event blocks consume their own taps.
                        .pointerInput(day, hourHeight) {
                            detectTapGestures { onSlot(day.atTime((it.y / (hourHeight.toPx())).toInt().coerceIn(0, 23), 0)) }
                        }
                        .drawBehind {
                            drawLine(border, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx())
                            for (h in 1 until 24) {
                                val y = (hourHeight * h).toPx()
                                drawLine(border, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                            }
                        },
                ) {
                    layoutDay(events, day, zone).forEach { p ->
                        val width = maxWidth / p.cols
                        EventBlock(
                            p.event,
                            Modifier
                                .offset(x = width * p.col, y = hourHeight * (p.startMin / 60f))
                                .size(width, hourHeight * ((p.endMin - p.startMin) / 60f))
                                .clickable { onEvent(p.event) },
                        )
                    }
                    if (day == today) NowLine(hourHeight * (now.toSecondOfDay() / 3600f))
                }
            }
        }
    }
}

@Composable
private fun DayLabel(day: LocalDate, isToday: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            day.dayOfWeek.getDisplayName(TextStyle.SHORT, LocalConfiguration.current.locales[0]),
            style = MaterialTheme.typography.labelSmall,
            color = if (isToday) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            Modifier.size(28.dp).background(if (isToday) accent else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                day.dayOfMonth.toString(),
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun HourLabels(hourHeight: Dp) {
    val format = rememberHourFormat()
    Box(Modifier.width(GUTTER).height(hourHeight * 24)) {
        for (h in 1 until 24) {
            Text(
                LocalTime.of(h, 0).format(format),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth().offset(y = hourHeight * h - 8.dp).padding(end = 6.dp),
            )
        }
    }
}

@Composable
private fun EventBlock(event: Event, modifier: Modifier) {
    val format = rememberTimeFormat()
    Column(
        modifier
            .padding(1.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(eventColor(event.color))
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Text(
            event.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            event.begin.atZone(ZoneId.systemDefault()).format(format),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = Color.White.copy(alpha = 0.8f),
            maxLines = 1,
        )
    }
}

@Composable
private fun NowLine(y: Dp) {
    val accent = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxWidth().offset(y = y - 1.dp).height(2.dp).background(accent))
    Box(Modifier.offset(x = (-4).dp, y = y - 5.dp).size(10.dp).background(accent, CircleShape))
}
