package dev.bornagojsic.calendar.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.bornagojsic.calendar.domain.Prefs
import dev.bornagojsic.calendar.ui.event.CUSTOM_REMINDER
import dev.bornagojsic.calendar.ui.event.ChoiceDialog
import dev.bornagojsic.calendar.ui.event.REMINDER_CHOICES
import dev.bornagojsic.calendar.ui.event.ReminderDialog
import dev.bornagojsic.calendar.ui.event.reminderLabel
import java.time.DayOfWeek
import java.time.format.TextStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefs: Prefs,
    onWeekStart: (DayOfWeek?) -> Unit,
    onClock24: (Boolean?) -> Unit,
    onDefaultReminders: (allDay: Boolean, List<Int>) -> Unit,
    onEventMinutes: (Int) -> Unit,
    onSnoozeMinutes: (Int) -> Unit,
    /** Keys of every account with calendars on the device; see `CalendarInfo.accountKey`. */
    deviceAccounts: List<String>,
    onAccountHidden: (key: String, hidden: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val locale = LocalConfiguration.current.locales[0]
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
            Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
                Choice(
                    "Week starts on",
                    listOf(null, DayOfWeek.MONDAY, DayOfWeek.SUNDAY, DayOfWeek.SATURDAY),
                    prefs.weekStart,
                    { it?.getDisplayName(TextStyle.FULL, locale) ?: "Phone default" },
                    onWeekStart,
                )
                HorizontalDivider()
                Choice(
                    "Time format",
                    listOf(null, false, true),
                    prefs.clock24,
                    { when (it) { null -> "Phone default"; false -> "12-hour"; true -> "24-hour" } },
                    onClock24,
                )
                HorizontalDivider()
                Choice("Default event length", listOf(15, 30, 60, 120), prefs.eventMinutes, ::duration, onEventMinutes)
                HorizontalDivider()
                DefaultReminders("Default notifications", prefs.timedReminders) { onDefaultReminders(false, it) }
                HorizontalDivider()
                DefaultReminders("Default all-day notifications", prefs.allDayReminders) { onDefaultReminders(true, it) }
                HorizontalDivider()
                Choice("Snooze for", listOf(5, 10, 15, 30, 60), prefs.snoozeMinutes, ::duration, onSnoozeMinutes)
                HorizontalDivider()
                DeviceAccounts(deviceAccounts, prefs.hiddenAccounts, onAccountHidden)
            }
        }
    }
}

private fun duration(minutes: Int) = if (minutes < 60) "$minutes minutes" else if (minutes == 60) "1 hour" else "${minutes / 60} hours"

@Composable
private fun Title(text: String) = Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 4.dp),
)

/** Accounts with calendars on the device; a switched-off account leaves the drawer, views and search but still notifies. */
@Composable
private fun DeviceAccounts(accounts: List<String>, hidden: Set<String>, onHidden: (String, Boolean) -> Unit) {
    Title("Accounts")
    accounts.forEach { key ->
        val shown = key !in hidden
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(shown, role = Role.Switch) { onHidden(key, !it) }
                .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
        ) {
            // Keys are `type/name`; account types don't contain a slash.
            val name = key.substringAfter('/')
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                // Same name under two account types (e.g. Google and DAVx5): show the type to tell them apart.
                if (accounts.count { it.substringAfter('/') == name } > 1) {
                    Text(key.substringBefore('/'), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Switch(shown, onCheckedChange = null)
        }
    }
}

@Composable
private fun DefaultReminders(title: String, reminders: List<Int>, onChange: (List<Int>) -> Unit) {
    // null: closed; false: the presets; true: the custom dialog.
    var custom by remember { mutableStateOf<Boolean?>(null) }
    fun add(minutes: Int) {
        if (minutes !in reminders) onChange((reminders + minutes).sorted())
    }
    Title(title)
    reminders.forEach { m ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp)) {
            Text(reminderLabel(m), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { onChange(reminders - m) }) { Icon(Icons.Default.Close, "Remove ${reminderLabel(m)}") }
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { custom = false }.padding(horizontal = 12.dp),
    ) {
        Icon(Icons.Default.Add, null, Modifier.padding(12.dp))
        Text("Add notification", style = MaterialTheme.typography.bodyLarge)
    }
    when (custom) {
        false -> ChoiceDialog(
            REMINDER_CHOICES - reminders.toSet() + CUSTOM_REMINDER, null,
            { if (it == CUSTOM_REMINDER) "Custom…" else reminderLabel(it) },
            // ChoiceDialog dismisses before picking, so "Custom…" can open the next dialog.
            { if (it == CUSTOM_REMINDER) custom = true else add(it) },
            { custom = null },
        )
        true -> ReminderDialog(::add) { custom = null }
        null -> Unit
    }
}

@Composable
private fun <T> Choice(title: String, options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Title(title)
    Column(Modifier.selectableGroup()) {
        options.forEach { option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(option == selected, role = Role.RadioButton) { onPick(option) }
                    .padding(horizontal = 12.dp),
            ) {
                RadioButton(option == selected, onClick = null, modifier = Modifier.padding(12.dp))
                Text(label(option), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
