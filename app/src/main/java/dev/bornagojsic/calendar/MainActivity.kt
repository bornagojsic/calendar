package dev.bornagojsic.calendar

import android.Manifest
import androidx.compose.runtime.produceState
import dev.bornagojsic.calendar.data.EventColor
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import dev.bornagojsic.calendar.ui.LocalPrefs
import dev.bornagojsic.calendar.domain.Prefs
import dev.bornagojsic.calendar.ui.schedule.SearchResults
import dev.bornagojsic.calendar.ui.settings.SettingsScreen
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
import java.time.Instant
import java.time.LocalDate
import android.os.Build
import androidx.compose.runtime.LaunchedEffect
import dev.bornagojsic.calendar.reminders.EXTRA_BEGIN
import dev.bornagojsic.calendar.reminders.EXTRA_EVENT_ID
import java.time.format.DateTimeFormatter

private val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

class MainActivity : ComponentActivity() {
    /** The occurrence a tapped reminder points at, until the screen shows it. */
    private var opening by mutableStateOf<Pair<Long, Instant>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only on a fresh start: after rotation the sheet is already open, or the user closed it.
        if (savedInstanceState == null) opening = intent.reminder()
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
                    if (granted) CalendarScreen(opening, onOpened = { opening = null }) else PermissionScreen(onResult = { granted = hasPermissions() })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.reminder()?.let { opening = it }
    }

    private fun Intent.reminder() = getLongExtra(EXTRA_EVENT_ID, -1).takeIf { it >= 0 }
        ?.let { it to Instant.ofEpochMilli(getLongExtra(EXTRA_BEGIN, 0)) }

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

/** One instance per process: the reminder receivers read it too. */
internal val Context.settings by preferencesDataStore("settings")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarScreen(opening: Pair<Long, Instant>?, onOpened: () -> Unit) {
    // Application context: the ViewModel outlives the Activity.
    val app = LocalContext.current.applicationContext
    val vm: CalendarViewModel = viewModel(factory = viewModelFactory {
        initializer { CalendarViewModel(CalendarRepository(app.contentResolver), app.settings) }
    })
    val calendars by vm.calendars.collectAsStateWithLifecycle()
    val allCalendars by vm.allCalendars.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val date by vm.date.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val titleFormat = remember { DateTimeFormatter.ofPattern("LLLL yyyy") }
    var picking by rememberSaveable { mutableStateOf(false) }
    val selected by vm.selected.collectAsStateWithLifecycle()
    val details by vm.details.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val onError: (Throwable) -> Unit = { Toast.makeText(context, "Couldn't save: ${it.message}", Toast.LENGTH_LONG).show() }
    fun create(start: LocalDateTime?) {
        val ok = if (start == null) vm.create() else vm.create(start)
        if (!ok) Toast.makeText(context, "No writable calendar", Toast.LENGTH_SHORT).show()
    }
    LaunchedEffect(opening) {
        opening?.let { (id, begin) -> vm.open(id, begin) }
        onOpened()
    }
    // Not part of the gate above: the calendar works without reminders. The system stops asking after two denials.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(enabled = query != null) { vm.search(null) }

    // Settings reach every view, sheet and dialog below.
    CompositionLocalProvider(LocalPrefs provides (prefs ?: Prefs())) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                CalendarDrawer(
                    mode = mode,
                    onMode = {
                        vm.setMode(it)
                        scope.launch { drawerState.close() }
                    },
                    // All accounts hidden: show no list rather than the "no calendars on this device" hint.
                    calendars = calendars.takeUnless { it.isNullOrEmpty() && !allCalendars.isNullOrEmpty() },
                    onToggle = { vm.setVisible(it.id, !it.visible) },
                    onSettings = {
                        settingsOpen = true
                        scope.launch { drawerState.close() }
                    },
                )
            },
        ) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    if (query != null) SearchBar(query.orEmpty(), vm::search) else TopAppBar(
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
                        actions = {
                            IconButton(onClick = { vm.search("") }) { Icon(Icons.Default.Search, contentDescription = "Search") }
                            TodayButton(onClick = { vm.setDate(LocalDate.now()) })
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    )
                },
                floatingActionButton = {
                    if (query == null) FloatingActionButton(onClick = { create(null) }) { Icon(Icons.Default.Add, contentDescription = "New event") }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    // key: each view keeps its own pager/list state; switching views (or the week start) starts fresh at `date`.
                    if (query != null) results?.let { SearchResults(it, vm::select) } else key(mode, prefs?.weekStart) {
                        when (val m = mode.takeIf { prefs != null }) {
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
        if (settingsOpen) prefs?.let {
            SettingsScreen(
                it, vm::setWeekStart, vm::setClock24,
                vm::setDefaultReminders, vm::setEventMinutes, vm::setSnoozeMinutes,
                allCalendars.orEmpty().map { c -> c.accountKey }.distinct(), vm::setAccountHidden,
                onClose = { settingsOpen = false },
            )
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
            // All calendars: a reminder can open an event of a hidden account.
            val cal = allCalendars?.find { c -> c.id == it.draft.calendarId }
            val palette by produceState(emptyList<EventColor>(), cal?.account, cal?.accountType) {
                // Clear first: the old account's colors mustn't stay pickable while the new ones load.
                value = emptyList()
                value = cal?.let { c -> vm.palette(c) }.orEmpty()
            }
            EventEditor(it, calendars.orEmpty() + listOfNotNull(cal?.takeIf { c -> c !in calendars.orEmpty() }), palette, vm.writing, vm::update, onSave = { s -> vm.save(s, onError) }, onClose = vm::closeEditor)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(query: String, onQuery: (String?) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        title = {
            TextField(
                query,
                onQuery,
                placeholder = { Text("Search") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        navigationIcon = {
            IconButton(onClick = { onQuery(null) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search") }
        },
        actions = {
            if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, contentDescription = "Clear") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
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
