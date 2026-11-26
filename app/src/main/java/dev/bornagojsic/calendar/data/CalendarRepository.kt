package dev.bornagojsic.calendar.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.database.ContentObserver
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Instances
import android.net.Uri
import dev.bornagojsic.calendar.domain.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant

data class CalendarInfo(
    val id: Long,
    val name: String,
    val account: String,
    val color: Int,
    val visible: Boolean,
)

/** Reads the system CalendarProvider. Requires READ_CALENDAR (and WRITE_CALENDAR for writes). */
class CalendarRepository(private val resolver: ContentResolver) {

    /** Emits the calendar list now and again whenever the provider changes (e.g. after a DAVx5 sync). */
    fun calendars(): Flow<List<CalendarInfo>> =
        changes(Calendars.CONTENT_URI).map { queryCalendars() }.flowOn(Dispatchers.IO)

    /** Occurrences of events in visible calendars that overlap [begin, end), re-queried on any provider change. */
    fun instances(begin: Instant, end: Instant): Flow<List<Event>> =
        changes(CalendarContract.CONTENT_URI).map { queryInstances(begin, end) }.flowOn(Dispatchers.IO)

    /** Emits now and again whenever anything under [uri] changes. */
    private fun changes(uri: Uri): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(uri, true, observer)
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.conflate()

    suspend fun setVisible(id: Long, visible: Boolean) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply { put(Calendars.VISIBLE, if (visible) 1 else 0) }
        resolver.update(ContentUris.withAppendedId(Calendars.CONTENT_URI, id), values, null, null)
    }

    private fun queryCalendars(): List<CalendarInfo> {
        val projection = arrayOf(
            Calendars._ID,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.ACCOUNT_NAME,
            Calendars.CALENDAR_COLOR,
            Calendars.VISIBLE,
        )
        val sort = "${Calendars.ACCOUNT_NAME}, ${Calendars.CALENDAR_DISPLAY_NAME}"
        return resolver.query(Calendars.CONTENT_URI, projection, null, null, sort)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        CalendarInfo(
                            id = c.getLong(0),
                            name = c.getString(1) ?: "",
                            account = c.getString(2) ?: "",
                            color = c.getInt(3),
                            visible = c.getInt(4) != 0,
                        )
                    )
                }
            }
        } ?: emptyList()
    }

    private fun queryInstances(begin: Instant, end: Instant): List<Event> {
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin.toEpochMilli())
            ContentUris.appendId(it, end.toEpochMilli())
        }.build()
        val projection = arrayOf(
            Instances._ID,
            Instances.EVENT_ID,
            Instances.TITLE,
            Instances.BEGIN,
            Instances.END,
            Instances.ALL_DAY,
            Instances.DISPLAY_COLOR,
        )
        val sort = "${Instances.BEGIN}, ${Instances.END} DESC"
        return resolver.query(uri, projection, "${Instances.VISIBLE} = 1", null, sort)?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Event(
                            id = c.getLong(0),
                            eventId = c.getLong(1),
                            title = c.getString(2) ?: "",
                            begin = Instant.ofEpochMilli(c.getLong(3)),
                            end = Instant.ofEpochMilli(c.getLong(4)),
                            allDay = c.getInt(5) != 0,
                            color = c.getInt(6),
                        )
                    )
                }
            }
        } ?: emptyList()
    }
}
