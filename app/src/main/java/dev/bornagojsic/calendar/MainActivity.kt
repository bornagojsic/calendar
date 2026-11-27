package dev.bornagojsic.calendar

import android.Manifest
import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.key
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.datastore.preferences.preferencesDataStore
import dev.bornagojsic.calendar.ui.DateDialog
import dev.bornagojsic.calendar.ui.day.TimeGridView
import dev.bornagojsic.calendar.ui.month.MonthView
import dev.bornagojsic.calendar.ui.schedule.ScheduleView
import android.content.Intent
import android.widget.Toast
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import dev.bornagojsic.calendar.ui.event.EventEditor
import dev.bornagojsic.calendar.ui.event.EventSheet
import java.time.LocalDateTime
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.bornagojsic.calendar.data.CalendarRepository
import dev.bornagojsic.calendar.ui.drawer.CalendarDrawer
import dev.bornagojsic.calendar.ui.theme.CalendarTheme
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark-only app: force light system bar icons even when the phone is in light mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            CalendarTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    var granted by remember { mutableStateOf(hasPermissions()) }
                    // Re-check on resume: the user may have granted access in system settings.
                    LifecycleResumeEffect(Unit) {
                        granted = hasPermissions()
                        onPauseOrDispose {}
                    }
                    if (granted) CalendarScreen() else PermissionScreen(onResult = { granted = hasPermissions() })
                }
            }
        }
    }

    private fun hasPermissions() = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
private fun PermissionScreen(onResult: () -> Unit) {
    val activity = LocalActivity.current ?: return
    // After a denial with no rationale allowed, the system dialog won't show again: send the user to settings.
    // Held in state and set on every result, since repeated denials change nothing else that would recompose.
    var blocked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        blocked = PERMISSIONS.none { activity.shouldShowRequestPermissionRationale(it) }
        onResult()
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Calendar access needed", style = MaterialTheme.typography.titleLarge)
        Button(onClick = {
            if (blocked) {
                activity.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))
                )
            } else {
                launcher.launch(PERMISSIONS)
            }
        }) { Text(if (blocked) "Open settings" else "Grant access") }
    }
}

private val Context.settings by preferencesDataStore("settings")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarScreen() {
    // Application context: the ViewModel outlives the Activity.
    val app = LocalContext.current.applicationContext
    val vm: CalendarViewModel = viewModel(factory = viewModelFactory {
        initializer { CalendarViewModel(CalendarRepository(app.contentResolver), app.settings) }
    })
    val calendars by vm.calendars.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val date by vm.date.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val titleFormat = remember { DateTimeFormatter.ofPattern("LLLL yyyy") }
    var picking by rememberSaveable { mutableStateOf(false) }
    val selected by vm.selected.collectAsStateWithLifecycle()
    val details by vm.details.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val onError: (Throwable) -> Unit = { Toast.makeText(context, "Couldn't save: ${it.message}", Toast.LENGTH_LONG).show() }
    fun create(start: LocalDateTime?) {
        val ok = if (start == null) vm.create() else vm.create(start)
        if (!ok) Toast.makeText(context, "No writable calendar", Toast.LENGTH_SHORT).show()
    }
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            CalendarDrawer(
                mode = mode,
                onMode = {
                    vm.setMode(it)
                    scope.launch { drawerState.close() }
                },
                calendars = calendars,
                onToggle = { vm.setVisible(it.id, !it.visible) },
            )
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            date.format(titleFormat),
                            modifier = Modifier.clickable(onClickLabel = "Pick a date") { picking = true },
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Open menu")
                        }
                    },
                    actions = { TodayButton(onClick = { vm.setDate(LocalDate.now()) }) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { create(null) }) { Icon(Icons.Default.Add, contentDescription = "New event") }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                // key: each view keeps its own pager/list state; switching views starts fresh at `date`.
                key(mode) {
                    when (val m = mode) {
                        null -> Unit
                        ViewMode.SCHEDULE -> ScheduleView(date, vm::setDate, vm::events, vm::select)
                        ViewMode.MONTH -> MonthView(date, vm::setDate, onDay = {
                            vm.setDate(it)
                            vm.setMode(ViewMode.DAY)
                        }, vm::events)
                        else -> TimeGridView(m.days, date, vm::setDate, vm::events, vm::select, onSlot = { create(it) })
                    }
                }
            }
        }
    }
    if (picking) DateDialog(date, onPick = { vm.setDate(it) }, onDismiss = { picking = false })
    selected?.let { event ->
        EventSheet(
            event = event,
            details = details?.takeIf { it.eventId == event.eventId },
            onDismiss = { vm.select(null) },
            onEdit = vm::edit,
            onDelete = { src, s -> vm.delete(src, s, onError) },
        )
    }
    vm.editor?.let {
        EventEditor(it, calendars.orEmpty(), vm::update, onSave = { s -> vm.save(s, onError) }, onClose = vm::closeEditor)
    }
}

@Composable
private fun TodayButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(
            Modifier.size(24.dp).border(1.5.dp, MaterialTheme.colorScheme.onSurface, MaterialTheme.shapes.extraSmall),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                LocalDate.now().dayOfMonth.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.semantics { contentDescription = "Today" },
            )
        }
    }
}
