package dev.bornagojsic.calendar

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bornagojsic.calendar.data.CalendarRepository
import dev.bornagojsic.calendar.domain.Event
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

enum class ViewMode(val label: String, val days: Int) {
    SCHEDULE("Schedule", 0),
    DAY("Day", 1),
    THREE_DAY("3 days", 3),
    WEEK("Week", 7),
    MONTH("Month", 0),
}

private val VIEW_MODE = stringPreferencesKey("view_mode")

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
}
