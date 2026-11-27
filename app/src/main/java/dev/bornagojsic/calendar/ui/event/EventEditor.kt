package dev.bornagojsic.calendar.ui.event

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.bornagojsic.calendar.Editor
import dev.bornagojsic.calendar.data.CalendarInfo
import dev.bornagojsic.calendar.data.Scope
import dev.bornagojsic.calendar.domain.Draft
import dev.bornagojsic.calendar.domain.Freq
import dev.bornagojsic.calendar.domain.Rule
import dev.bornagojsic.calendar.ui.DateDialog
import dev.bornagojsic.calendar.ui.eventColor
import dev.bornagojsic.calendar.ui.rememberTimeFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

private val REMINDER_CHOICES = listOf(0, 5, 10, 15, 30, 60, 120, 1_440, 2_880, 10_080)

private enum class Dialog { START_DATE, START_TIME, END_DATE, END_TIME, ZONE, REPEAT, CUSTOM, CALENDAR, REMINDER, SCOPE }

/** Full-screen editor for a new or existing event. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditor(
    editor: Editor,
    calendars: List<CalendarInfo>,
    onChange: (Draft) -> Unit,
    onSave: (Scope) -> Unit,
    onClose: () -> Unit,
) {
    val d = editor.draft
    val source = editor.source?.details
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    val dateFormat = remember { DateTimeFormatter.ofPattern("EEE, d MMM yyyy") }
    val timeFormat = rememberTimeFormat()
    val rule = d.rrule?.let { Rule.parse(it, d.zone) }
    val writable = calendars.filter { it.writable }
    val calendar = calendars.find { it.id == d.calendarId }
    BackHandler(onBack = onClose)
    val titleFocus = remember { FocusRequester() }
    // New events start in the title, as in Google Calendar.
    LaunchedEffect(Unit) { if (editor.source == null) titleFocus.requestFocus() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().imePadding()) {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Discard") } },
                actions = {
                    TextButton(
                        enabled = d.valid,
                        onClick = { if (source?.recurring == true) dialog = Dialog.SCOPE else onSave(Scope.ALL) },
                    ) { Text("Save") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
            Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
                PlainField(d.title, { onChange(d.copy(title = it)) }, "Add title", MaterialTheme.typography.headlineSmall, Modifier.padding(start = 40.dp).focusRequester(titleFocus))
                HorizontalDivider()
                EditorRow(null, Modifier.toggleable(d.allDay, role = Role.Switch) { onChange(d.reshape(allDay = it)) }) {
                    Text("All day", Modifier.weight(1f))
                    Switch(d.allDay, onCheckedChange = null)
                }
                DateTimeRow(d.start, d.allDay, dateFormat, timeFormat, true, { dialog = Dialog.START_DATE }, { dialog = Dialog.START_TIME })
                DateTimeRow(d.end, d.allDay, dateFormat, timeFormat, d.valid, { dialog = Dialog.END_DATE }, { dialog = Dialog.END_TIME })
                if (!d.allDay) {
                    EditorRow(null, Modifier.clickable { dialog = Dialog.ZONE }) { Text(d.zone.id) }
                }
                EditorRow(Icons.Default.Refresh, Modifier.clickable { dialog = Dialog.REPEAT }) {
                    Text(repeatLabel(d.rrule, rule, d.start.toLocalDate()))
                }
                HorizontalDivider()
                // Recurring events stay in their calendar: moving one means copying the series, which drops its exceptions.
                val canMove = source?.recurring != true && writable.size > 1
                EditorRow(null, if (canMove) Modifier.clickable { dialog = Dialog.CALENDAR } else Modifier) {
                    Box(Modifier.size(12.dp).background(eventColor(calendar?.color ?: 0), CircleShape))
                    Text(calendar?.name ?: "", Modifier.padding(start = 12.dp))
                }
                HorizontalDivider()
                d.reminders.forEach { m ->
                    EditorRow(Icons.Default.Notifications) {
                        Text(reminderLabel(m), Modifier.weight(1f))
                        IconButton(onClick = { onChange(d.copy(reminders = d.reminders - m)) }) {
                            Icon(Icons.Default.Close, "Remove", Modifier.size(18.dp))
                        }
                    }
                }
                EditorRow(if (d.reminders.isEmpty()) Icons.Default.Notifications else null, Modifier.clickable { dialog = Dialog.REMINDER }) {
                    Text("Add notification", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationOn, null, Modifier.padding(start = 16.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    PlainField(d.location, { onChange(d.copy(location = it)) }, "Add location")
                }
                HorizontalDivider()
                PlainField(d.description, { onChange(d.copy(description = it)) }, "Add description", modifier = Modifier.padding(start = 40.dp), singleLine = false)
            }
        }
    }

    fun dismiss() {
        dialog = null
    }
    when (dialog) {
        Dialog.START_DATE -> DateDialog(d.start.toLocalDate(), { onChange(d.moveStart(it.atTime(d.start.toLocalTime()))) }, ::dismiss)
        Dialog.END_DATE -> DateDialog(d.end.toLocalDate(), { onChange(d.copy(end = it.atTime(d.end.toLocalTime()))) }, ::dismiss)
        Dialog.START_TIME -> TimeDialog(d.start.toLocalTime(), { onChange(d.moveStart(d.start.with(it))) }, ::dismiss)
        Dialog.END_TIME -> TimeDialog(d.end.toLocalTime(), { onChange(d.copy(end = d.end.with(it))) }, ::dismiss)
        Dialog.ZONE -> ZoneDialog({ onChange(d.reshape(zone = it)) }, ::dismiss)
        Dialog.REPEAT -> {
            val start = d.start.toLocalDate()
            val presets = Rule.presets(start)
            // Index into options; the extra last index is "Custom…".
            val options = listOf<Rule?>(null) + presets + listOfNotNull(rule?.takeIf { it !in presets })
            ChoiceDialog(
                items = (0..options.size).toList(),
                selected = if (d.rrule == null) 0 else options.indexOf(rule).takeIf { rule != null && it >= 0 },
                label = { if (it == options.size) "Custom…" else options[it]?.describe(start) ?: "Does not repeat" },
                onPick = {
                    if (it == options.size) dialog = Dialog.CUSTOM
                    else onChange(d.copy(rrule = options[it]?.format(d.zone, d.allDay)))
                },
                onDismiss = ::dismiss,
            )
        }
        Dialog.CUSTOM -> RecurrenceDialog(
            initial = rule ?: Rule(Freq.WEEKLY, days = setOf(d.start.dayOfWeek)),
            onDone = { onChange(d.copy(rrule = it.format(d.zone, d.allDay))) },
            onDismiss = ::dismiss,
        )
        Dialog.CALENDAR -> ChoiceDialog(writable, calendar, { it.name }, { onChange(d.copy(calendarId = it.id)) }, ::dismiss)
        Dialog.REMINDER -> ChoiceDialog(
            REMINDER_CHOICES - d.reminders.toSet(), null, ::reminderLabel,
            { onChange(d.copy(reminders = (d.reminders + it).sorted())) }, ::dismiss,
        )
        Dialog.SCOPE -> ScopeDialog(
            title = "Save recurring event",
            // A changed rule can't apply to a single occurrence.
            scopes = if (d.rrule == source?.rrule) Scope.entries else Scope.entries - Scope.THIS,
            confirm = "Save",
            onConfirm = onSave,
            onDismiss = ::dismiss,
        )
        null -> Unit
    }
}

private fun repeatLabel(rrule: String?, rule: Rule?, start: LocalDate) = when {
    rrule == null -> "Does not repeat"
    rule == null -> "Custom recurrence"
    else -> rule.describe(start)
}

@Composable
private fun EditorRow(icon: ImageVector?, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(24.dp)) {
            icon?.let { Icon(it, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(16.dp))
        content()
    }
}

@Composable
private fun DateTimeRow(
    at: LocalDateTime,
    allDay: Boolean,
    dateFormat: DateTimeFormatter,
    timeFormat: DateTimeFormatter,
    valid: Boolean,
    onDate: () -> Unit,
    onTime: () -> Unit,
) {
    val color = if (valid) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
    EditorRow(null) {
        Text(at.format(dateFormat), Modifier.weight(1f).clickable(onClick = onDate).padding(vertical = 8.dp), color = color)
        if (!allDay) {
            Text(
                at.format(timeFormat),
                Modifier.clickable(onClick = onTime).padding(8.dp),
                color = color,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun PlainField(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
) {
    TextField(
        value = value,
        onValueChange = onValue,
        placeholder = { Text(placeholder, style = style) },
        textStyle = style,
        singleLine = singleLine,
        modifier = modifier.fillMaxWidth(),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(time: LocalTime, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(time.hour, time.minute, DateFormat.is24HourFormat(LocalContext.current))
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state) },
        confirmButton = {
            TextButton(onClick = {
                onPick(LocalTime.of(state.hour, state.minute))
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ZoneDialog(onPick: (ZoneId) -> Unit, onDismiss: () -> Unit) {
    val zones = remember { ZoneId.getAvailableZoneIds().filter { '/' in it && !it.startsWith("Etc/") }.sorted() }
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search time zones") }, singleLine = true)
        },
        text = {
            LazyColumn {
                items(zones.filter { it.contains(query.trim().replace(' ', '_'), ignoreCase = true) }) {
                    Text(
                        it,
                        Modifier.fillMaxWidth().clickable {
                            onPick(ZoneId.of(it))
                            onDismiss()
                        }.padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun <T> ChoiceDialog(items: List<T>, selected: T?, label: (T) -> String, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                items.forEach {
                    Row(
                        Modifier.fillMaxWidth().selectable(it == selected, role = Role.RadioButton) {
                            onDismiss()
                            onPick(it)
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(it == selected, onClick = null)
                        Text(label(it), Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Custom rule builder: every N units, weekdays for weekly, and how it ends. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RecurrenceDialog(initial: Rule, onDone: (Rule) -> Unit, onDismiss: () -> Unit) {
    var interval by remember { mutableStateOf(initial.interval.toString()) }
    var freq by remember { mutableStateOf(initial.freq) }
    var days by remember { mutableStateOf(initial.days) }
    var until by remember { mutableStateOf(initial.until) }
    var count by remember { mutableStateOf(initial.count?.toString()) }
    var pickingUntil by remember { mutableStateOf(false) }
    val n = interval.toIntOrNull()?.takeIf { it > 0 }
    val c = count?.toIntOrNull()?.takeIf { it > 0 }
    val ok = n != null && (count == null || c != null)
    val locale = LocalConfiguration.current.locales[0]
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Repeats every") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        interval, { interval = it.filter(Char::isDigit).take(3) },
                        Modifier.width(72.dp), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Freq.entries.forEach {
                            FilterChip(freq == it, { freq = it }, { Text(it.unit + if (n == 1) "" else "s") })
                        }
                    }
                }
                if (freq == Freq.WEEKLY) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DayOfWeek.entries.forEach { day ->
                            FilterChip(
                                day in days,
                                { days = if (day in days) days - day else days + day },
                                { Text(day.getDisplayName(TextStyle.NARROW, locale)) },
                            )
                        }
                    }
                }
                Text("Ends", style = MaterialTheme.typography.titleSmall)
                EndOption(until == null && count == null, "Never") { until = null; count = null }
                EndOption(until != null, "On ${until?.format(DateTimeFormatter.ofPattern("d MMM yyyy")) ?: "a date"}") {
                    count = null
                    pickingUntil = true
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EndOption(count != null, "After", Modifier.weight(1f)) {
                        until = null
                        if (count == null) count = "10"
                    }
                    OutlinedTextField(
                        count ?: "", { count = it.filter(Char::isDigit).take(4) },
                        Modifier.width(80.dp), singleLine = true, enabled = count != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Text(" times", Modifier.padding(start = 4.dp))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = {
                onDone(Rule(freq, n!!, if (freq == Freq.WEEKLY) days else emptySet(), until, c))
                onDismiss()
            }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (pickingUntil) DateDialog(until ?: LocalDate.now().plusMonths(1), { until = it }, { pickingUntil = false })
}

@Composable
private fun EndOption(selected: Boolean, text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.selectable(selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null)
        Text(text, Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp))
    }
}
