package dev.bornagojsic.calendar.ui.event

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.bornagojsic.calendar.data.Scope
import dev.bornagojsic.calendar.domain.Event
import dev.bornagojsic.calendar.domain.EventDetails
import dev.bornagojsic.calendar.domain.Rule
import dev.bornagojsic.calendar.domain.Source
import dev.bornagojsic.calendar.ui.eventColor
import dev.bornagojsic.calendar.ui.label
import dev.bornagojsic.calendar.ui.rememberTimeFormat
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Event detail: opens half-height, drags up to full screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventSheet(
    event: Event,
    details: EventDetails?,
    onDismiss: () -> Unit,
    onEdit: (Source) -> Unit,
    onDelete: (Source, Scope) -> Unit,
) {
    var deleting by remember { mutableStateOf(false) }
    val timeFormat = rememberTimeFormat()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        // Full height so the sheet has somewhere to drag to; it opens partially expanded.
        Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.End) {
                if (details?.writable == true) {
                    IconButton(onClick = { onEdit(Source(event, details)) }) { Icon(Icons.Default.Edit, "Edit") }
                    IconButton(onClick = { deleting = true }) { Icon(Icons.Default.Delete, "Delete") }
                }
            }
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.padding(top = 8.dp, end = 16.dp).size(16.dp)
                        .background(eventColor(event.color), MaterialTheme.shapes.extraSmall),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(event.label, style = MaterialTheme.typography.headlineSmall)
                    Text(whenText(event, timeFormat), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                }
            }
            details ?: return@Column
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                details.rrule?.let {
                    val start = event.begin.atZone(if (details.allDay) ZoneOffset.UTC else ZoneId.systemDefault()).toLocalDate()
                    DetailRow(Icons.Default.Refresh, Rule.parse(it, details.zone)?.describe(start) ?: "Custom recurrence")
                }
                if (details.location.isNotBlank()) DetailRow(Icons.Default.LocationOn, details.location)
                if (details.description.isNotBlank()) DetailRow(null, details.description)
                details.reminders.forEach { DetailRow(Icons.Default.Notifications, reminderLabel(it)) }
                DetailRow(Icons.Default.DateRange, details.calendarName)
            }
        }
    }
    if (deleting && details != null) {
        ScopeDialog(
            title = if (details.recurring) "Delete recurring event" else "Delete this event?",
            scopes = if (details.recurring) Scope.entries else emptyList(),
            confirm = "Delete",
            onConfirm = { onDelete(Source(event, details), it) },
            onDismiss = { deleting = false },
        )
    }
}

@Composable
private fun DetailRow(icon: ImageVector?, text: String) {
    Row {
        Box(Modifier.size(32.dp)) {
            icon?.let { Icon(it, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** "Friday, 27 November · 10:00 – 11:00", or a date range for multi-day events. */
private fun whenText(event: Event, timeFormat: DateTimeFormatter): String {
    val zone = ZoneId.systemDefault()
    val days = event.days(zone)
    val long = DateTimeFormatter.ofPattern("EEEE, d MMMM")
    val short = DateTimeFormatter.ofPattern("d MMM")
    return when {
        event.allDay && days.start == days.endInclusive -> days.start.format(long)
        event.allDay -> "${days.start.format(short)} – ${days.endInclusive.format(short)}"
        days.start == days.endInclusive && event.end.atZone(zone).toLocalDate() == days.start ->
            "${days.start.format(long)} · ${event.begin.atZone(zone).format(timeFormat)} – ${event.end.atZone(zone).format(timeFormat)}"
        // The end's own date: an event ending at midnight ends on the next day, not the last day it covers.
        else -> "${days.start.format(short)}, ${event.begin.atZone(zone).format(timeFormat)} – " +
            "${event.end.atZone(zone).format(short)}, ${event.end.atZone(zone).format(timeFormat)}"
    }
}

fun reminderLabel(minutes: Int): String {
    fun unit(n: Int, name: String) = "$n $name${if (n == 1) "" else "s"} before"
    return when {
        minutes == 0 -> "At time of event"
        minutes % 10_080 == 0 -> unit(minutes / 10_080, "week")
        minutes % 1_440 == 0 -> unit(minutes / 1_440, "day")
        minutes % 60 == 0 -> unit(minutes / 60, "hour")
        else -> unit(minutes, "minute")
    }
}

/** Asks which occurrences to apply to; with no [scopes] it's a plain confirmation that applies to [Scope.ALL]. */
@Composable
fun ScopeDialog(title: String, scopes: List<Scope>, confirm: String, onConfirm: (Scope) -> Unit, onDismiss: () -> Unit) {
    var scope by remember { mutableStateOf(scopes.firstOrNull() ?: Scope.ALL) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = if (scopes.isEmpty()) null else {
            {
                Column {
                    scopes.forEach {
                        Row(
                            Modifier.fillMaxWidth().selectable(scope == it, role = Role.RadioButton) { scope = it },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(scope == it, onClick = null)
                            Text(it.label, Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(scope)
                onDismiss()
            }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
