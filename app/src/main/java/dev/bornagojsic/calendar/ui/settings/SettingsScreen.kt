package dev.bornagojsic.calendar.ui.settings

import android.accounts.Account
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.bornagojsic.calendar.domain.Prefs
import java.time.DayOfWeek
import java.time.format.TextStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefs: Prefs,
    onWeekStart: (DayOfWeek?) -> Unit,
    onClock24: (Boolean?) -> Unit,
    onSyncMinutes: (Int) -> Unit,
    accounts: List<Account>,
    onAddAccount: (url: String, user: String, password: String, onResult: (String?) -> Unit) -> Unit,
    onRemoveAccount: (Account, (String?) -> Unit) -> Unit,
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
                Accounts(accounts, onAddAccount, onRemoveAccount)
                if (accounts.isNotEmpty()) {
                    HorizontalDivider()
                    Choice(
                        "Sync every",
                        listOf(15, 30, 60, 240),
                        prefs.syncMinutes,
                        { if (it < 60) "$it minutes" else if (it == 60) "1 hour" else "${it / 60} hours" },
                        onSyncMinutes,
                    )
                }
            }
        }
    }
}

@Composable
private fun Title(text: String) = Text(
    text,
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 4.dp),
)

/** The app's own CalDAV accounts. */
@Composable
private fun Accounts(
    accounts: List<Account>,
    onAdd: (String, String, String, (String?) -> Unit) -> Unit,
    onRemove: (Account, (String?) -> Unit) -> Unit,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<Account?>(null) }
    Title("Accounts")
    accounts.forEach { account ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp)) {
            Text(account.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { removing = account }) { Icon(Icons.Default.Delete, "Remove ${account.name}") }
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { adding = true }.padding(horizontal = 12.dp),
    ) {
        Icon(Icons.Default.Add, null, Modifier.padding(12.dp))
        Text("Add CalDAV account", style = MaterialTheme.typography.bodyLarge)
    }
    if (adding) AddAccountDialog(onAdd, onDismiss = { adding = false })
    removing?.let { account ->
        var busy by remember(account) { mutableStateOf(false) }
        var error by remember(account) { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!busy) removing = null },
            title = { Text("Remove ${account.name}?") },
            text = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    error = null
                    onRemove(account) { e ->
                        busy = false
                        if (e == null) removing = null else error = e
                    }
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }, enabled = !busy) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AddAccountDialog(onAdd: (String, String, String, (String?) -> Unit) -> Unit, onDismiss: () -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    // Not saveable: the password stays out of saved instance state.
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Add CalDAV account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val next = KeyboardOptions(imeAction = ImeAction.Next)
                OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, singleLine = true,
                    keyboardOptions = next.copy(keyboardType = KeyboardType.Uri))
                OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, keyboardOptions = next)
                OutlinedTextField(password, { password = it }, label = { Text("App password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && url.isNotBlank() && user.isNotBlank() && password.isNotEmpty(),
                onClick = {
                    busy = true
                    error = null
                    onAdd(url, user.trim(), password) { e ->
                        busy = false
                        if (e == null) onDismiss() else error = e
                    }
                },
            ) { Text(if (busy) "Adding…" else "Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
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
