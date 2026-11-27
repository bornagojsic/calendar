package dev.bornagojsic.calendar.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import dev.bornagojsic.calendar.domain.Event
import dev.bornagojsic.calendar.domain.Prefs
import java.time.DayOfWeek
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
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

/** The user's Settings, provided once at the root. */
val LocalPrefs = staticCompositionLocalOf { Prefs() }

/** The week's first day: the Settings override, else the phone locale's. */
@Composable
fun firstDayOfWeek(): DayOfWeek = LocalPrefs.current.firstDayOfWeek(LocalConfiguration.current.locales[0])

/** The Settings override, else the phone's 12h/24h setting. */
@Composable
fun is24Hour(): Boolean = LocalPrefs.current.clock24 ?: DateFormat.is24HourFormat(LocalContext.current)

/** Follows [is24Hour]. */
@Composable
fun rememberTimeFormat(): DateTimeFormatter = rememberClockFormat("HH:mm", "h:mm a")

/** Hour-only labels for the time grid gutter ("13:00" or "1 PM"). */
@Composable
fun rememberHourFormat(): DateTimeFormatter = rememberClockFormat("HH:mm", "h a")

@Composable
private fun rememberClockFormat(pattern24: String, pattern12: String): DateTimeFormatter {
    val h24 = is24Hour()
    return remember(h24) { DateTimeFormatter.ofPattern(if (h24) pattern24 else pattern12) }
}

/** Vertical step between stacked chips, in sp so it grows with the chip text under large font scales. */
@Composable
fun chipPitch(): Dp = with(LocalDensity.current) { 20.sp.toDp() }

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

/** M3 date picker in a dialog. The picker works in UTC-midnight millis. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateDialog(date: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state) }
}
