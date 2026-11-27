package dev.bornagojsic.calendar.data

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.database.ContentObserver
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Colors
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import android.provider.CalendarContract.Instances
import android.net.Uri
import dev.bornagojsic.calendar.domain.Draft
import dev.bornagojsic.calendar.domain.Event
import dev.bornagojsic.calendar.domain.EventDetails
import dev.bornagojsic.calendar.domain.Source
import dev.bornagojsic.calendar.domain.exdate
import dev.bornagojsic.calendar.domain.exdatesFrom
import dev.bornagojsic.calendar.domain.shiftExdate
import dev.bornagojsic.calendar.domain.untilBefore
import dev.bornagojsic.calendar.domain.withUntil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

data class CalendarInfo(
    val id: Long,
    val name: String,
    val account: String,
    val accountType: String,
    val color: Int,
    val visible: Boolean,
    val writable: Boolean,
) {
    /** Identifies the account in `Prefs.hiddenAccounts`. */
    val accountKey get() = "$accountType/$account"
}

/** One entry of an account's event color palette; [key] is what the event row stores. */
data class EventColor(val key: String, val color: Int)

/** The reminders the editor shows and rewrites; email and other methods are left alone. */
private val ALERTS = "${Reminders.METHOD} IN (${Reminders.METHOD_DEFAULT}, ${Reminders.METHOD_ALERT})"

/** Which occurrences of a recurring event an edit or delete applies to. */
enum class Scope(val label: String) { THIS("This event"), FOLLOWING("This and following events"), ALL("All events") }

/** Reads the system CalendarProvider. Requires READ_CALENDAR (and WRITE_CALENDAR for writes). */
class CalendarRepository(private val resolver: ContentResolver) {

    /** Emits the calendar list now and again whenever the provider changes (e.g. after a DAVx5 sync). */
    fun calendars(): Flow<List<CalendarInfo>> =
        changes(Calendars.CONTENT_URI).map { queryCalendars() }.flowOn(Dispatchers.IO)

    /** Occurrences of events in visible calendars that overlap [begin, end), re-queried on any provider change. */
    fun instances(begin: Instant, end: Instant, hidden: Set<Long> = emptySet()): Flow<List<Event>> =
        changes(CalendarContract.CONTENT_URI).map { queryInstances(begin, end, hidden = hidden) }.flowOn(Dispatchers.IO)

    /**
     * Occurrences in [begin, end) matching [query], re-queried on any provider change. The provider's search URI
     * matches every word (case-insensitive LIKE) against title, description, location and attendee names/emails.
     */
    fun search(query: String, begin: Instant, end: Instant, hidden: Set<Long> = emptySet()): Flow<List<Event>> =
        changes(CalendarContract.CONTENT_URI).map { queryInstances(begin, end, query, hidden) }.flowOn(Dispatchers.IO)

    /** The occurrence of [eventId] starting at [begin], or null if it's gone or its calendar is hidden. Hidden accounts still count: their reminders notify. */
    suspend fun instance(eventId: Long, begin: Instant): Event? = withContext(Dispatchers.IO) {
        queryInstances(begin, begin).find { it.eventId == eventId && it.begin == begin }
    }

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

    /** The event colors [cal]'s account offers (DAVx5 fills these in); empty when it offers none. */
    suspend fun palette(cal: CalendarInfo): List<EventColor> = withContext(Dispatchers.IO) {
        resolver.query(
            Colors.CONTENT_URI, arrayOf(Colors.COLOR_KEY, Colors.COLOR),
            "${Colors.ACCOUNT_NAME} = ? AND ${Colors.ACCOUNT_TYPE} = ? AND ${Colors.COLOR_TYPE} = ${Colors.TYPE_EVENT}",
            arrayOf(cal.account, cal.accountType), null,
        )?.use { c -> buildList { while (c.moveToNext()) add(EventColor(c.getString(0), c.getInt(1))) } } ?: emptyList()
    }

    private fun queryCalendars(): List<CalendarInfo> {
        val projection = arrayOf(
            Calendars._ID,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.ACCOUNT_NAME,
            Calendars.CALENDAR_COLOR,
            Calendars.VISIBLE,
            Calendars.CALENDAR_ACCESS_LEVEL,
            Calendars.ACCOUNT_TYPE,
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
                            writable = c.getInt(5) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                            accountType = c.getString(6) ?: "",
                        )
                    )
                }
            }
        } ?: emptyList()
    }

    private fun queryInstances(begin: Instant, end: Instant, search: String? = null, hidden: Set<Long> = emptySet()): List<Event> {
        val uri = (if (search == null) Instances.CONTENT_URI else Instances.CONTENT_SEARCH_URI).buildUpon().also {
            ContentUris.appendId(it, begin.toEpochMilli())
            ContentUris.appendId(it, end.toEpochMilli())
            search?.let(it::appendPath)
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
        var where = "${Instances.VISIBLE} = 1 AND (${Instances.STATUS} IS NULL OR ${Instances.STATUS} != ${Instances.STATUS_CANCELED})"
        if (hidden.isNotEmpty()) where += " AND ${Instances.CALENDAR_ID} NOT IN (${hidden.joinToString()})"
        return resolver.query(uri, projection, where, null, sort)?.use { c ->
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

    /** The event row behind an occurrence, re-read on any provider change; null once it's gone. */
    fun details(eventId: Long): Flow<EventDetails?> =
        changes(CalendarContract.CONTENT_URI).map { queryDetails(eventId) }.flowOn(Dispatchers.IO)

    private fun queryDetails(id: Long): EventDetails? {
        val projection = arrayOf(
            Events.CALENDAR_ID,
            Events.CALENDAR_DISPLAY_NAME,
            Events.CALENDAR_ACCESS_LEVEL,
            Events.TITLE,
            Events.EVENT_LOCATION,
            Events.DESCRIPTION,
            Events.ALL_DAY,
            Events.EVENT_TIMEZONE,
            Events.RRULE,
            Events.DTSTART,
            Events.ORIGINAL_ID,
            Events.ORIGINAL_INSTANCE_TIME,
            Events._SYNC_ID,
            Events.EXDATE,
            Events.DURATION,
            Events.RDATE,
            Events.EVENT_COLOR_KEY,
            Events.EVENT_COLOR,
        )
        val details = resolver.query(eventUri(id), projection, "${Events.DELETED} = 0", null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val originalId = if (c.isNull(10)) null else c.getLong(10)
            EventDetails(
                eventId = id,
                calendarId = c.getLong(0),
                calendarName = c.getString(1) ?: "",
                // shortcut: RDATE series are read-only, since every write path here assumes one RRULE. Support when needed.
                writable = c.getInt(2) >= Calendars.CAL_ACCESS_CONTRIBUTOR && c.isNull(15),
                title = c.getString(3) ?: "",
                location = c.getString(4) ?: "",
                description = c.getString(5) ?: "",
                allDay = c.getInt(6) != 0,
                zone = c.getString(7)?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault(),
                rrule = c.getString(8),
                seriesId = originalId ?: id,
                seriesStart = Instant.ofEpochMilli(c.getLong(9)),
                originalTime = if (originalId == null || c.isNull(11)) null else Instant.ofEpochMilli(c.getLong(11)),
                synced = !c.isNull(12),
                exdate = c.getString(13),
                duration = c.getString(14),
                reminders = emptyList(),
                colorKey = c.getString(16),
                color = if (c.isNull(17)) null else c.getInt(17),
            )
        } ?: return null
        // An exception row has no RRULE of its own: the series rule and start live on its master.
        val series = if (details.seriesId == id) details else resolver.query(
            eventUri(details.seriesId), arrayOf(Events.RRULE, Events.DTSTART, Events._SYNC_ID, Events.EXDATE, Events.DURATION, Events.RDATE), null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) details.copy(
                rrule = c.getString(0),
                seriesStart = Instant.ofEpochMilli(c.getLong(1)),
                synced = !c.isNull(2),
                exdate = c.getString(3),
                duration = c.getString(4),
                // An exception has no RDATE of its own; a master with one makes the whole set read-only.
                writable = details.writable && c.isNull(5),
            ) else null
        } ?: details
        val reminders = resolver.query(
            Reminders.CONTENT_URI, arrayOf(Reminders.MINUTES), "${Reminders.EVENT_ID} = ? AND $ALERTS", arrayOf("$id"), null,
        )?.use { c -> buildList { while (c.moveToNext()) add(c.getInt(0)) } } ?: emptyList()
        return series.copy(reminders = reminders.distinct().sorted())
    }

    /** Creates an event ([src] null) or applies [draft] to [src] across [scope], in one batch. */
    suspend fun save(draft: Draft, src: Source?, scope: Scope) = withContext(Dispatchers.IO) {
        val ops = ArrayList<ContentProviderOperation>()
        val d = src?.details
        when {
            d == null -> insert(ops, draft)
            !d.recurring -> if (draft.calendarId != d.calendarId) {
                // Moving between calendars: sync adapters expect a new row in the target calendar, not a changed CALENDAR_ID.
                insert(ops, draft)
                ops += ContentProviderOperation.newDelete(eventUri(d.eventId)).build()
            } else {
                update(ops, d.eventId, draft)
            }
            scope == Scope.THIS -> if (d.originalTime != null) {
                update(ops, d.eventId, draft.copy(rrule = null), exception = true)
            } else if (!d.synced) {
                exclude(ops, d, src.originalTime)
                insert(ops, draft.copy(rrule = null))
            } else {
                val at = ops.size
                ops += ContentProviderOperation.newInsert(exceptionUri(d.seriesId))
                    .withValues(values(draft.copy(rrule = null)).apply {
                        // The provider rejects DTEND/RRULE on a new exception ("can't overwrite dtend"); it takes DURATION.
                        remove(Events.CALENDAR_ID)
                        remove(Events.RRULE)
                        remove(Events.DTEND)
                        put(Events.DURATION, draft.duration())
                        put(Events.ORIGINAL_INSTANCE_TIME, src.originalTime.toEpochMilli())
                        // The provider crashes on a new exception carrying a color key (seen on API 36); it's set below.
                        remove(Events.EVENT_COLOR_KEY)
                    })
                    .build()
                // Always: the provider copies the master's key into the exception, and "Calendar color" must clear it.
                ops += ContentProviderOperation.newUpdate(Events.CONTENT_URI)
                    .withSelection("${Events._ID} = ?", arrayOf(""))
                    .withSelectionBackReference(0, at)
                    .withValue(Events.EVENT_COLOR_KEY, draft.colorKey)
                    .withValue(Events.EVENT_COLOR, draft.color)
                    .build()
                // The provider may copy the master's reminders onto the exception; replace them.
                ops += ContentProviderOperation.newDelete(Reminders.CONTENT_URI)
                    .withSelection("${Reminders.EVENT_ID} = ? AND $ALERTS", arrayOf(""))
                    .withSelectionBackReference(0, at)
                    .build()
                addReminders(ops, draft.reminders) { it.withValueBackReference(Reminders.EVENT_ID, at) }
            }
            // Editing the first occurrence's "following" is the whole series.
            scope == Scope.ALL || src.originalTime == d.seriesStart -> {
                // The draft holds this occurrence's times; move the series start, and its EXDATEs, by the same
                // wall-clock amount, so a move across a DST change keeps the hour.
                val zone = if (d.allDay) ZoneOffset.UTC else d.zone
                val wallShift = Duration.between(LocalDateTime.ofInstant(src.originalTime, zone), draft.start)
                val moved = LocalDateTime.ofInstant(d.seriesStart, zone) + wallShift
                val begin = if (draft.allDay) moved.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant() else moved.atZone(draft.zone).toInstant()
                val newZone = if (draft.allDay) ZoneOffset.UTC else draft.zone
                update(ops, d.seriesId, draft, begin = begin, exdate = d.exdate?.let { shiftExdate(it, wallShift, zone, newZone) })
            }
            else -> {
                val at = src.originalTime
                // An unchanged COUNT counts from the series start; the new series gets what's left.
                fun count(r: String?) = r?.let { Regex("COUNT=(\\d+)").find(it)?.groupValues?.get(1)?.toInt() }
                val count = count(draft.rrule)
                // Compare only COUNT: moving the start to another weekday rewrites BYDAY but not the count.
                val rrule = if (count == null || count != count(d.rrule)) draft.rrule
                else draft.rrule!!.replace("COUNT=$count", "COUNT=${(count - occurrencesBefore(d, at)).coerceAtLeast(1)}")
                truncate(ops, d, at)
                // Occurrences deleted after the split stay deleted, moved by however far this one moved.
                val zone = if (d.allDay) ZoneOffset.UTC else d.zone
                val wallShift = Duration.between(LocalDateTime.ofInstant(at, zone), draft.start)
                val newZone = if (draft.allDay) ZoneOffset.UTC else draft.zone
                val exdate = exdatesFrom(d.exdate, at, d.allDay)?.let { shiftExdate(it, wallShift, zone, newZone) }
                insert(ops, draft.copy(rrule = rrule), exdate = if (rrule != null) exdate else null)
            }
        }
        resolver.applyBatch(CalendarContract.AUTHORITY, ops)
    }

    /** Occurrences the series generated before [at]: still listed, moved or cancelled (exceptions), or excluded. */
    private fun occurrencesBefore(d: EventDetails, at: Instant): Int {
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, d.seriesStart.toEpochMilli())
            ContentUris.appendId(it, at.toEpochMilli())
        }.build()
        val listed = resolver.query(
            uri, arrayOf(Instances._ID), "${Instances.EVENT_ID} = ? AND ${Instances.BEGIN} < ?",
            arrayOf("${d.seriesId}", "${at.toEpochMilli()}"), null,
        )?.use { it.count } ?: 0
        val exceptions = resolver.query(
            Events.CONTENT_URI, arrayOf(Events._ID), "${Events.ORIGINAL_ID} = ? AND ${Events.ORIGINAL_INSTANCE_TIME} < ?",
            arrayOf("${d.seriesId}", "${at.toEpochMilli()}"), null,
        )?.use { it.count } ?: 0
        val excluded = d.exdate.orEmpty().split(',').count { it.isNotBlank() } - (exdatesFrom(d.exdate, at, d.allDay)?.split(',')?.size ?: 0)
        return listed + exceptions + excluded
    }

    /** Deletes [src] across [scope]. */
    suspend fun delete(src: Source, scope: Scope) = withContext(Dispatchers.IO) {
        val d = src.details
        val ops = ArrayList<ContentProviderOperation>()
        when {
            !d.recurring || scope == Scope.ALL || (scope == Scope.FOLLOWING && src.originalTime == d.seriesStart) ->
                ops += ContentProviderOperation.newDelete(eventUri(d.seriesId)).build()
            // A single occurrence is removed by a cancelled exception, never by deleting a row.
            scope == Scope.THIS && d.originalTime == null && !d.synced -> exclude(ops, d, src.originalTime)
            scope == Scope.THIS -> ops += if (d.originalTime != null) {
                ContentProviderOperation.newUpdate(eventUri(d.eventId))
                    .withValue(Events.STATUS, Events.STATUS_CANCELED)
                    // Timing columns make the provider re-expand, dropping the cancelled occurrence.
                    .withValue(Events.DTSTART, src.event.begin.toEpochMilli())
                    .withValue(Events.DTEND, src.event.end.toEpochMilli())
                    .build()
            } else {
                ContentProviderOperation.newInsert(exceptionUri(d.seriesId))
                    .withValue(Events.ORIGINAL_INSTANCE_TIME, src.originalTime.toEpochMilli())
                    .withValue(Events.STATUS, Events.STATUS_CANCELED)
                    .build()
            }
            else -> truncate(ops, d, src.originalTime)
        }
        resolver.applyBatch(CalendarContract.AUTHORITY, ops)
    }

    /**
     * Drops the occurrence at [at] from the series via EXDATE. Used instead of an exception row while the series has
     * no _SYNC_ID: the provider stops expanding a master that has exceptions but no sync id (seen on API 36).
     */
    private fun exclude(ops: ArrayList<ContentProviderOperation>, d: EventDetails, at: Instant) {
        ops += ContentProviderOperation.newUpdate(eventUri(d.seriesId))
            .withValues(seriesValues(d))
            .withValue(Events.EXDATE, listOfNotNull(d.exdate?.takeIf { it.isNotBlank() }, exdate(at, d.allDay)).joinToString(","))
            .build()
    }

    /** Ends the series just before [at] and drops its exceptions from [at] on. */
    private fun truncate(ops: ArrayList<ContentProviderOperation>, d: EventDetails, at: Instant) {
        ops += ContentProviderOperation.newUpdate(eventUri(d.seriesId))
            .withValues(seriesValues(d))
            .withValue(Events.RRULE, withUntil(d.rrule!!, untilBefore(at, d.allDay)))
            .build()
        ops += ContentProviderOperation.newDelete(Events.CONTENT_URI)
            .withSelection("${Events.ORIGINAL_ID} = ? AND ${Events.ORIGINAL_INSTANCE_TIME} >= ?", arrayOf("${d.seriesId}", "${at.toEpochMilli()}"))
            .build()
    }

    /**
     * The master's timing columns, unchanged. The provider only re-expands instances when an update carries DTSTART
     * (AOSP updateInstancesLocked), so rule-only edits must resend them.
     */
    private fun seriesValues(d: EventDetails) = ContentValues().apply {
        put(Events.DTSTART, d.seriesStart.toEpochMilli())
        put(Events.DURATION, d.duration)
        put(Events.RRULE, d.rrule)
    }

    private fun insert(ops: ArrayList<ContentProviderOperation>, draft: Draft, exdate: String? = null) {
        val at = ops.size
        ops += ContentProviderOperation.newInsert(Events.CONTENT_URI)
            .withValues(values(draft).apply { exdate?.let { put(Events.EXDATE, it) } })
            .build()
        addReminders(ops, draft.reminders) { it.withValueBackReference(Reminders.EVENT_ID, at) }
    }

    private fun update(
        ops: ArrayList<ContentProviderOperation>,
        id: Long,
        draft: Draft,
        begin: Instant = draft.begin(),
        exception: Boolean = false,
        exdate: String? = null,
    ) {
        ops += ContentProviderOperation.newUpdate(eventUri(id))
            .withValues(values(draft, begin).apply {
                if (exception) remove(Events.CALENDAR_ID)
                exdate?.let { put(Events.EXDATE, it) }
            })
            .build()
        ops += ContentProviderOperation.newDelete(Reminders.CONTENT_URI)
            .withSelection("${Reminders.EVENT_ID} = ? AND $ALERTS", arrayOf("$id"))
            .build()
        addReminders(ops, draft.reminders) { it.withValue(Reminders.EVENT_ID, id) }
    }

    private fun addReminders(
        ops: ArrayList<ContentProviderOperation>,
        minutes: List<Int>,
        event: (ContentProviderOperation.Builder) -> ContentProviderOperation.Builder,
    ) {
        minutes.forEach {
            ops += event(ContentProviderOperation.newInsert(Reminders.CONTENT_URI))
                .withValue(Reminders.MINUTES, it)
                .withValue(Reminders.METHOD, Reminders.METHOD_ALERT)
                .build()
        }
    }

    /** Event columns for [d], starting at [begin] (the draft's own start unless a series is being shifted). */
    private fun values(d: Draft, begin: Instant = d.begin()) = ContentValues().apply {
        put(Events.CALENDAR_ID, d.calendarId)
        put(Events.TITLE, d.title)
        put(Events.EVENT_LOCATION, d.location)
        put(Events.DESCRIPTION, d.description)
        put(Events.ALL_DAY, if (d.allDay) 1 else 0)
        put(Events.EVENT_TIMEZONE, if (d.allDay) "UTC" else d.zone.id)
        // A key makes the provider fill EVENT_COLOR from the palette; an unkeyed color (set by another app) is kept as is.
        put(Events.EVENT_COLOR_KEY, d.colorKey)
        if (d.color == null) putNull(Events.EVENT_COLOR) else put(Events.EVENT_COLOR, d.color)
        put(Events.HAS_ALARM, if (d.reminders.isEmpty()) 0 else 1)
        put(Events.DTSTART, begin.toEpochMilli())
        // Recurring events take DURATION instead of DTEND.
        if (d.rrule != null) {
            put(Events.RRULE, d.rrule)
            put(Events.DURATION, d.duration())
            putNull(Events.DTEND)
        } else {
            putNull(Events.RRULE)
            putNull(Events.DURATION)
            put(Events.DTEND, (begin + Duration.between(d.begin(), d.endInstant())).toEpochMilli())
        }
    }

    private fun eventUri(id: Long) = ContentUris.withAppendedId(Events.CONTENT_URI, id)
    private fun exceptionUri(seriesId: Long) = ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, seriesId)
}
