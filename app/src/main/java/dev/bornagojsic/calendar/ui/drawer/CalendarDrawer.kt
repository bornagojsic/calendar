package dev.bornagojsic.calendar.ui.drawer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import dev.bornagojsic.calendar.ViewMode
import dev.bornagojsic.calendar.data.CalendarInfo

@Composable
fun CalendarDrawer(
    mode: ViewMode?,
    onMode: (ViewMode) -> Unit,
    calendars: List<CalendarInfo>?,
    onToggle: (CalendarInfo) -> Unit,
    onSettings: () -> Unit,
) {
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        // One list for everything, so the calendars stay reachable when the drawer is short (landscape).
        LazyColumn {
            item {
                Text(
                    "Calendar",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                )
                HorizontalDivider()
            }
            items(ViewMode.entries) {
                NavigationDrawerItem(
                    label = { Text(it.label) },
                    selected = it == mode,
                    onClick = { onMode(it) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
            item { HorizontalDivider(Modifier.padding(top = 8.dp)) }
            when {
                calendars == null -> Unit
                calendars.isEmpty() -> item {
                    Text(
                        "No calendars on this device. Add your Nextcloud account in DAVx5.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                else -> calendars.groupBy { it.account }.forEach { (account, list) ->
                    item(key = "account:$account") {
                        Text(
                            account,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(list, key = { it.id }) { cal -> CalendarRow(cal, onToggle) }
                }
            }
            item {
                HorizontalDivider(Modifier.padding(top = 8.dp))
                NavigationDrawerItem(
                    label = { Text("Settings") },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    selected = false,
                    onClick = onSettings,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun CalendarRow(cal: CalendarInfo, onToggle: (CalendarInfo) -> Unit) {
    val color = Color(cal.color)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = cal.visible, role = Role.Checkbox, onValueChange = { onToggle(cal) })
            .padding(horizontal = 12.dp),
    ) {
        Checkbox(
            checked = cal.visible,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = color, uncheckedColor = color),
            modifier = Modifier.padding(12.dp),
        )
        Column { Text(cal.name, style = MaterialTheme.typography.bodyLarge) }
    }
}
