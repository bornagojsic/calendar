package dev.bornagojsic.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bornagojsic.calendar.data.CalendarRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CalendarViewModel(private val repo: CalendarRepository) : ViewModel() {
    /** null until the first query finishes, so the UI can tell "loading" from "no calendars". */
    val calendars = repo.calendars().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setVisible(id: Long, visible: Boolean) {
        viewModelScope.launch { repo.setVisible(id, visible) }
    }
}
