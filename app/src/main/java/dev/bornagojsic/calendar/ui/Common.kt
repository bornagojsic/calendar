package dev.bornagojsic.calendar.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import dev.bornagojsic.calendar.domain.Event
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Loads events for a day range; the ViewModel's `events`. */
typealias EventLoader = (first: LocalDate, last: LocalDate) -> Flow<List<Event>>

/** Calendar colors toned to one lightness so white text stays legible on the dark canvas. */
fun eventColor(argb: Int): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(argb, hsl)
    hsl[1] = hsl[1].coerceAtMost(0.65f)
    hsl[2] = 0.38f
    return Color(ColorUtils.HSLToColor(hsl))
}

val Event.label get() = title.ifBlank { "(No title)" }

/** Follows the phone's 12h/24h setting. */
@Composable
fun rememberTimeFormat(): DateTimeFormatter = rememberClockFormat("HH:mm", "h:mm a")

/** Hour-only labels for the time grid gutter ("13:00" or "1 PM"). */
@Composable
fun rememberHourFormat(): DateTimeFormatter = rememberClockFormat("HH:mm", "h a")

@Composable
private fun rememberClockFormat(pattern24: String, pattern12: String): DateTimeFormatter {
    val context = LocalContext.current
    return remember { DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) pattern24 else pattern12) }
}

@Composable
fun EventChip(event: Event, modifier: Modifier = Modifier) {
    Text(
        event.label,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .background(eventColor(event.color), MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

private const val CENTER = Int.MAX_VALUE / 2

/**
 * Endless horizontal pager over date ranges. Page `CENTER + k` shows `rangeOf(k)`.
 * Keeps the pager and [date] in sync both ways: swiping moves [date] into the new page,
 * and an outside change (today, month picker) jumps to the page holding it.
 */
@Composable
fun DatePager(
    date: LocalDate,
    onDate: (LocalDate) -> Unit,
    offsetOf: (LocalDate) -> Int,
    rangeOf: (Int) -> ClosedRange<LocalDate>,
    modifier: Modifier = Modifier,
    page: @Composable (ClosedRange<LocalDate>) -> Unit,
) {
    val state = rememberPagerState(CENTER + offsetOf(date)) { Int.MAX_VALUE }
    val currentDate by rememberUpdatedState(date)
    LaunchedEffect(date) {
        val target = CENTER + offsetOf(date)
        if (target != state.currentPage) state.scrollToPage(target)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.settledPage }.collect {
            val range = rangeOf(it - CENTER)
            if (currentDate !in range) onDate(range.start)
        }
    }
    HorizontalPager(state, modifier, beyondViewportPageCount = 1) { page(rangeOf(it - CENTER)) }
}
