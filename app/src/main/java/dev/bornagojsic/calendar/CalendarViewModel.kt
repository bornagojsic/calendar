package dev.bornagojsic.calendar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import android.accounts.Account
import dev.bornagojsic.calendar.sync.CalDavAccounts
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bornagojsic.calendar.data.CalendarRepository
import dev.bornagojsic.calendar.data.CalendarInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.bornagojsic.calendar.data.Scope
import dev.bornagojsic.calendar.domain.Draft
import dev.bornagojsic.calendar.domain.Event
import dev.bornagojsic.calendar.domain.Prefs
import dev.bornagojsic.calendar.domain.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.ZoneId

enum class ViewMode(val label: String, val days: Int) {
    SCHEDULE("Schedule", 0),
    DAY("Day", 1),
    THREE_DAY("3 days", 3),
    WEEK("Week", 7),
    MONTH("Month", 0),
}

private val VIEW_MODE = stringPreferencesKey("view_mode")
private val WEEK_START = stringPreferencesKey("week_start")
private val CLOCK = stringPreferencesKey("clock")
private val SYNC_MINUTES = intPreferencesKey("sync_minutes")

/** How far around today search looks; Instances expands every recurrence in the range, so keep it bounded. */
const val SEARCH_YEARS = 2L

/** The open editor: a draft, and the occurrence it edits (null for a new event). */
data class Editor(val draft: Draft, val source: Source?)

class CalendarViewModel(
    private val repo: CalendarRepository,
    private val settings: DataStore<Preferences>,
    private val caldav: CalDavAccounts,
) : ViewModel() {
    /** null until the first query finishes, so the UI can tell "loading" from "no calendars". */
    val calendars = repo.calendars().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Last used view, Schedule on first launch. null while DataStore loads, so the UI doesn't flash the default. */
    val mode = settings.data
        .map { p -> ViewMode.entries.find { it.name == p[VIEW_MODE] } ?: ViewMode.SCHEDULE }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Week start and 12h/24h overrides. null while DataStore loads, so views don't open on the locale's week first. */
    val prefs = settings.data
        .map { p ->
            Prefs(
                weekStart = DayOfWeek.entries.find { it.name == p[WEEK_START] },
                clock24 = p[CLOCK]?.let { it == "24" },
                syncMinutes = p[SYNC_MINUTES] ?: Prefs().syncMinutes,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** One key per call, so two quick changes can't overwrite each other with a stale copy. */
    fun setWeekStart(day: DayOfWeek?) {
        viewModelScope.launch { settings.edit { p -> day?.let { p[WEEK_START] = it.name } ?: p.remove(WEEK_START) } }
    }

    fun setClock24(h24: Boolean?) {
        viewModelScope.launch { settings.edit { p -> h24?.let { p[CLOCK] = if (it) "24" else "12" } ?: p.remove(CLOCK) } }
    }

    fun setSyncMinutes(minutes: Int) {
        viewModelScope.launch { settings.edit { it[SYNC_MINUTES] = minutes } }
        caldav.setSyncMinutes(minutes)
    }

    /** The app's own CalDAV accounts. */
    val accounts = caldav.accounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Adds a CalDAV account; [onResult] gets null on success, else a message for the user. */
    fun addAccount(url: String, user: String, password: String, onResult: (String?) -> Unit) =
        accountOp(onResult) { caldav.add(url, user, password, prefs.value?.syncMinutes ?: Prefs().syncMinutes) }

    /** Removes a CalDAV account; [onResult] gets null on success, else a message for the user. */
    fun removeAccount(account: Account, onResult: (String?) -> Unit) = accountOp(onResult) { caldav.remove(account) }

    private fun accountOp(onResult: (String?) -> Unit, op: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                op()
                onResult(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** True while a pull-to-refresh sync runs. */
    var refreshing by mutableStateOf(false)
        private set

    fun refresh() {
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            try {
                caldav.refresh()
            } finally {
                refreshing = false
            }
        }
    }

    private val _query = MutableStateFlow<String?>(null)

    /** The search text; null while search is closed. */
    val query = _query.asStateFlow()

    fun search(query: String?) {
        _query.value = query
    }

    /** Matches for [query], or null while there's nothing to show (closed, blank, or the first query runs). */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val results = _query
        .map { it?.trim().orEmpty() }
        .distinctUntilChanged()
        .debounce(250)
        .flatMapLatest { q ->
            if (q.isEmpty()) return@flatMapLatest flowOf(null)
            val today = LocalDate.now()
            val zone = ZoneId.systemDefault()
            repo.search(
                q,
                today.minusYears(SEARCH_YEARS).atStartOfDay(zone).toInstant(),
                today.plusYears(SEARCH_YEARS).atStartOfDay(zone).toInstant(),
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _date = MutableStateFlow(LocalDate.now())

    /** The date the current view is showing; drives the title, "today" and the month picker. */
    val date = _date.asStateFlow()

    fun setVisible(id: Long, visible: Boolean) {
        viewModelScope.launch { repo.setVisible(id, visible) }
    }

    fun setMode(mode: ViewMode) {
        viewModelScope.launch { settings.edit { it[VIEW_MODE] = mode.name } }
    }

    fun setDate(date: LocalDate) {
        _date.value = date
    }

    /** Occurrences touching any day in [first, last]. */
    fun events(first: LocalDate, last: LocalDate): Flow<List<Event>> {
        val zone = ZoneId.systemDefault()
        val range = first..last
        // Pad a day each side: all-day events sit on UTC midnights, which can fall outside the local window.
        return repo.instances(
            first.minusDays(1).atStartOfDay(zone).toInstant(),
            last.plusDays(2).atStartOfDay(zone).toInstant(),
        ).map { list -> list.filter { e -> e.days(zone).let { it.start <= range.endInclusive && it.endInclusive >= range.start } } }
    }

    private val _selected = MutableStateFlow<Event?>(null)

    /** The occurrence whose detail sheet is open. */
    val selected = _selected.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val details = _selected
        .flatMapLatest { e -> e?.let { repo.details(it.eventId) } ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun select(event: Event?) {
        _selected.value = event
    }

    /** Shows the occurrence a reminder points at: its day, with its detail sheet open. */
    fun open(eventId: Long, begin: Instant) {
        viewModelScope.launch {
            val event = repo.instance(eventId, begin) ?: return@launch
            _date.value = event.days(ZoneId.systemDefault()).start
            _selected.value = event
        }
    }

    /** Snapshot state, not a flow: text fields need synchronous updates. Lives here so edits survive rotation. */
    var editor by mutableStateOf<Editor?>(null)
        private set

    /** Opens the editor for a new event at [start], one hour long. False when there's no calendar to write to. */
    fun create(start: LocalDateTime = defaultStart()): Boolean {
        val cals = calendars.value.orEmpty().filter { it.writable }
        val cal = cals.firstOrNull { it.visible } ?: cals.firstOrNull() ?: return false
        editor = Editor(Draft(calendarId = cal.id, start = start, end = start.plusHours(1)), null)
        return true
    }

    /** The next full hour on the shown date; after 23:00 that's midnight of the following day. */
    private fun defaultStart(): LocalDateTime = date.value.atTime(LocalTime.now()).truncatedTo(ChronoUnit.HOURS).plusHours(1)

    suspend fun palette(cal: CalendarInfo) = repo.palette(cal)

    fun edit(source: Source) {
        _selected.value = null
        editor = Editor(Draft.of(source), source)
    }

    /** Ignored while a save runs: its success closes the editor, which would drop anything typed meanwhile. */
    fun update(draft: Draft) {
        if (!writing) editor = editor?.copy(draft = draft)
    }

    fun closeEditor() {
        editor = null
    }

    /** Writes the draft; the editor stays open with the user's input if the write fails. */
    fun save(scope: Scope, onError: (Throwable) -> Unit) {
        val e = editor ?: return
        launchWrite(onError) {
            repo.save(e.draft, e.source, scope)
            // Only the editor that was saved: the user may have closed it and opened another meanwhile.
            if (editor === e) editor = null
        }
    }

    fun delete(source: Source, scope: Scope, onError: (Throwable) -> Unit) {
        launchWrite(onError) {
            repo.delete(source, scope)
            _selected.value = null
        }
    }

    /** True while a save or delete runs; a second tap would insert a duplicate. */
    var writing by mutableStateOf(false)
        private set

    private fun launchWrite(onError: (Throwable) -> Unit, block: suspend () -> Unit) {
        if (writing) return
        writing = true
        viewModelScope.launch {
            try {
                block()
                caldav.upload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            } finally {
                writing = false
            }
        }
    }
}
