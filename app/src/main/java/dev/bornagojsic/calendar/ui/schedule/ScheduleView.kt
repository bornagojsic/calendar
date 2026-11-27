package dev.bornagojsic.calendar.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.bornagojsic.calendar.domain.Event
import dev.bornagojsic.calendar.ui.EventLoader
import dev.bornagojsic.calendar.ui.eventColor
import dev.bornagojsic.calendar.ui.label
import dev.bornagojsic.calendar.ui.rememberTimeFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import androidx.compose.ui.platform.LocalConfiguration

private const val PAST_DAYS = 90L
private const val FUTURE_DAYS = 365L
private const val MAX_YEARS = 10L

/** Endless list of days that have events, plus today and the date it opened on. The window grows by a year when you scroll near either end. */
@Composable
fun ScheduleView(date: LocalDate, onDate: (LocalDate) -> Unit, load: EventLoader, onEvent: (Event) -> Unit) {
    var from by rememberSaveable { mutableLongStateOf(date.minusDays(PAST_DAYS).toEpochDay()) }
    var to by rememberSaveable { mutableLongStateOf(date.plusDays(FUTURE_DAYS).toEpochDay()) }
    // The date the window was centered on; always gets a row, like today, so the list has something to land on.
    var anchor by rememberSaveable { mutableLongStateOf(date.toEpochDay()) }
    // The last loaded window and its events. Kept across window changes so the list doesn't blank out while a wider range loads.
    var loaded by remember { mutableStateOf<Pair<LongRange, List<Event>>?>(null) }
    LaunchedEffect(from, to) {
        load(LocalDate.ofEpochDay(from), LocalDate.ofEpochDay(to)).collect { loaded = (from..to) to it }
    }
    // A date outside the window (month picker) recenters it.
    LaunchedEffect(date) {
        if (date.toEpochDay() !in from..to) {
            from = date.minusDays(PAST_DAYS).toEpochDay()
            to = date.plusDays(FUTURE_DAYS).toEpochDay()
            anchor = date.toEpochDay()
        }
    }
    val (window, events) = loaded ?: return
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    // Today anchors the list; when it's outside the window (month picker), the picked date does.
    val days = remember(window, events, anchor, today) {
        groupByDay(events, zone, LocalDate.ofEpochDay(window.first), LocalDate.ofEpochDay(window.last), listOf(today, LocalDate.ofEpochDay(anchor)))
    }

    // Row i stands for every date in (days[i-1], days[i]], so empty dates map to the next day with events.
    fun indexOf(d: LocalDate) = days.indexOfFirst { it.first >= d }.let { if (it < 0) days.lastIndex else it }
    fun covers(i: Int, d: LocalDate) = d <= days[i].first && (i == 0 || d > days[i - 1].first)

    val list = rememberLazyListState(indexOf(date))
    // Top row by key, not index: indices shift when the window grows into the past.
    fun topIndex() = (list.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? Long)
        ?.let { key -> days.indexOfFirst { it.first.toEpochDay() == key } }?.takeIf { it >= 0 }
    val currentDate by rememberUpdatedState(date)
    // Both effects below wait while `date` is outside the loaded window (a recentered window is still loading),
    // so the old window's rows can't overwrite the picked date.
    LaunchedEffect(date, days) {
        if (date.toEpochDay() !in window) return@LaunchedEffect
        val i = topIndex() ?: return@LaunchedEffect
        if (!covers(i, date)) list.scrollToItem(indexOf(date))
    }
    // Keyed on the window too: a grown window that adds no rows must still allow the next growth.
    LaunchedEffect(list, days, window) {
        var grown = false
        snapshotFlow { list.layoutInfo.visibleItemsInfo.map { it.key } }.collect {
            val i = topIndex() ?: return@collect
            if (currentDate.toEpochDay() in window && !covers(i, currentDate)) onDate(days[i].first)
            if (grown) return@collect
            // Grow by a year near either end, up to MAX_YEARS from today, so sparse calendars still terminate.
            if (it.size >= days.size - i - 5 && to < today.plusYears(MAX_YEARS).toEpochDay()) {
                to += FUTURE_DAYS
                grown = true
            }
            if (i < 5 && from > today.minusYears(MAX_YEARS).toEpochDay()) {
                from -= FUTURE_DAYS
                grown = true
            }
        }
    }

    // A screen of bottom padding, so even the last row (today, a picked date) can sit at the top.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        DayList(days, zone, onEvent, list, PaddingValues(bottom = maxHeight))
    }
}

/** Search results, listed like the Schedule: by day, under month headers. */
@Composable
fun SearchResults(events: List<Event>, onEvent: (Event) -> Unit) {
    val zone = ZoneId.systemDefault()
    val days = remember(events) {
        if (events.isEmpty()) emptyList()
        else groupByDay(events, zone, events.minOf { it.days(zone).start }, events.maxOf { it.days(zone).endInclusive }, emptyList())
    }
    if (days.isEmpty()) {
        Text("No results", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        DayList(days, zone, onEvent, rememberLazyListState(), PaddingValues())
    }
}

@Composable
private fun DayList(
    days: List<Pair<LocalDate, List<Event>>>,
    zone: ZoneId,
    onEvent: (Event) -> Unit,
    list: LazyListState,
    padding: PaddingValues,
) {
    val today = LocalDate.now()
    val monthFormat = remember { DateTimeFormatter.ofPattern("LLLL yyyy") }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = padding) {
        itemsIndexed(days, key = { _, (d, _) -> d.toEpochDay() }) { i, (day, dayEvents) ->
            Column {
                if (i == 0 || days[i - 1].first.month != day.month || days[i - 1].first.year != day.year) {
                    Text(
                        day.format(monthFormat),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
                    )
                }
                DayRow(day, day == today, dayEvents, zone, onEvent)
            }
        }
    }
}

/** Days in [first, last] with their events; multi-day events appear on each day. [always] days in range get a row even when empty. */
private fun groupByDay(events: List<Event>, zone: ZoneId, first: LocalDate, last: LocalDate, always: List<LocalDate>): List<Pair<LocalDate, List<Event>>> {
    val map = sortedMapOf<LocalDate, MutableList<Event>>()
    always.filter { it in first..last }.forEach { map[it] = mutableListOf() }
    for (e in events) {
        val r = e.days(zone)
        var d = maxOf(r.start, first)
        while (d <= minOf(r.endInclusive, last)) {
            map.getOrPut(d) { mutableListOf() }.add(e)
            d = d.plusDays(1)
        }
    }
    return map.map { (d, list) -> d to list.sortedWith(compareBy({ !it.allDay }, { it.begin })) }
}

@Composable
private fun DayRow(day: LocalDate, isToday: Boolean, events: List<Event>, zone: ZoneId, onEvent: (Event) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.width(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                day.dayOfWeek.getDisplayName(TextStyle.SHORT, LocalConfiguration.current.locales[0]),
                style = MaterialTheme.typography.labelSmall,
                color = if (isToday) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                Modifier.size(32.dp).background(if (isToday) accent else Color.Transparent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    day.dayOfMonth.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 8.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (events.isEmpty()) {
                Text("No events", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            events.forEach { ScheduleEvent(it, zone, onEvent) }
        }
    }
}

@Composable
private fun ScheduleEvent(event: Event, zone: ZoneId, onEvent: (Event) -> Unit) {
    val format = rememberTimeFormat()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(eventColor(event.color))
            .clickable { onEvent(event) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(event.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = Color.White)
        Text(
            if (event.allDay) "All day" else "${event.begin.atZone(zone).format(format)} – ${event.end.atZone(zone).format(format)}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = Color.White.copy(alpha = 0.8f),
        )
    }
}
