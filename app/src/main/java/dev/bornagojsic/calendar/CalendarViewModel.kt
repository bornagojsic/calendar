package dev.bornagojsic.calendar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bornagojsic.calendar.data.CalendarRepository
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.bornagojsic.calendar.data.Scope
import dev.bornagojsic.calendar.domain.Draft
import dev.bornagojsic.calendar.domain.Event
import dev.bornagojsic.calendar.domain.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
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

/** The open editor: a draft, and the occurrence it edits (null for a new event). */
data class Editor(val draft: Draft, val source: Source?)

class CalendarViewModel(
    private val repo: CalendarRepository,
    private val settings: DataStore<Preferences>,
) : ViewModel() {
    /** null until the first query finishes, so the UI can tell "loading" from "no calendars". */
    val calendars = repo.calendars().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Last used view, Schedule on first launch. null while DataStore loads, so the UI doesn't flash the default. */
    val mode = settings.data
        .map { p -> ViewMode.entries.find { it.name == p[VIEW_MODE] } ?: ViewMode.SCHEDULE }
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
    private fun defaultStart(): LocalDateTime {
        val now = LocalDateTime.now()
        val next = now.truncatedTo(ChronoUnit.HOURS).plusHours(1)
        return date.value.atTime(next.toLocalTime()).plusDays(ChronoUnit.DAYS.between(now.toLocalDate(), next.toLocalDate()))
    }

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
