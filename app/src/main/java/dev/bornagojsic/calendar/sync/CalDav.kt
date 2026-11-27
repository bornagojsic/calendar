package dev.bornagojsic.calendar.sync

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.OperationApplicationException
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import android.database.Cursor
import at.bitfire.dav4jvm.QuotedStringUtils
import at.bitfire.dav4jvm.ktor.DavCalendar
import at.bitfire.dav4jvm.ktor.DavResource
import at.bitfire.dav4jvm.ktor.PreemptiveBasicDigestAuthProvider
import at.bitfire.dav4jvm.ktor.exception.DavException
import at.bitfire.dav4jvm.ktor.exception.NotFoundException
import at.bitfire.dav4jvm.ktor.exception.PreconditionFailedException
import at.bitfire.dav4jvm.ktor.exception.UnauthorizedException
import at.bitfire.dav4jvm.ktor.responses
import at.bitfire.dav4jvm.ktor.resolve
import at.bitfire.dav4jvm.ktor.withTrailingSlash
import at.bitfire.dav4jvm.property.caldav.CalDAV
import at.bitfire.dav4jvm.property.caldav.CalendarColor
import at.bitfire.dav4jvm.property.caldav.CalendarData
import at.bitfire.dav4jvm.property.caldav.CalendarHomeSet
import at.bitfire.dav4jvm.property.caldav.GetCTag
import at.bitfire.dav4jvm.property.caldav.SupportedCalendarComponentSet
import at.bitfire.dav4jvm.property.webdav.CurrentUserPrincipal
import at.bitfire.dav4jvm.property.webdav.CurrentUserPrivilegeSet
import at.bitfire.dav4jvm.property.webdav.DisplayName
import at.bitfire.dav4jvm.property.webdav.GetETag
import at.bitfire.dav4jvm.property.webdav.ResourceType
import at.bitfire.dav4jvm.property.webdav.WebDAV
import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.statement.bodyAsText
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.encodedPath
import io.ktor.http.headersOf
import io.ktor.http.hostWithPort
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import java.io.IOException
import java.util.UUID

/** A VEVENT calendar collection on the server. */
data class RemoteCalendar(val url: Url, val name: String, val color: Int?, val writable: Boolean)

/** A request to a host other than the account's server, refused so the login isn't sent there. */
class OriginRefusedException(message: String) : DavException(message)

/** True if [a] and [b] have the same scheme, host and port. */
fun sameOrigin(a: Url, b: Url) = a.protocol == b.protocol && a.host.equals(b.host, ignoreCase = true) && a.port == b.port

/**
 * dav4jvm follows redirects itself, so the client must not. It also follows them to any host, and the auth provider
 * sends the password with every request, so requests outside [origin] are refused before they go out.
 */
fun davClient(user: String, password: String, origin: Url) = HttpClient(OkHttp) {
    followRedirects = false
    install(Auth) { providers += PreemptiveBasicDigestAuthProvider(user, password) }
    install(createClientPlugin("SameOrigin") {
        onRequest { request, _ ->
            val url = request.url.build()
            if (!sameOrigin(url, origin)) throw OriginRefusedException("Refused to send the login to ${url.protocol.name}://${url.hostWithPort}")
        }
    })
}

/** The calendar home: .well-known/caldav (RFC 6764), else [base] itself → current-user-principal → calendar-home-set. */
suspend fun findHome(client: HttpClient, base: Url): Url {
    val wellKnown = URLBuilder(base).apply { encodedPath = "/.well-known/caldav" }.build()
    for (url in listOf(wellKnown, base)) {
        val r = try {
            DavResource(client, url).propfind(0, CalDAV.CalendarHomeSet, WebDAV.CurrentUserPrincipal).responses().firstOrNull()
        } catch (e: UnauthorizedException) {
            throw e
        } catch (_: DavException) {
            null
        } ?: continue
        r[CalendarHomeSet::class.java]?.hrefs?.firstOrNull()?.let { return r.href.resolve(it)!!.withTrailingSlash() }
        val principal = r[CurrentUserPrincipal::class.java]?.href?.let { r.href.resolve(it) } ?: continue
        DavResource(client, principal).propfind(0, CalDAV.CalendarHomeSet).responses().firstOrNull()
            ?.get(CalendarHomeSet::class.java)?.hrefs?.firstOrNull()
            ?.let { return principal.resolve(it)!!.withTrailingSlash() }
    }
    throw IOException("No CalDAV server found at $base")
}

/** Calendars in [home] that hold events, and whether the listing is complete (no failed or truncated responses). */
suspend fun remoteCalendars(client: HttpClient, home: Url): Pair<List<RemoteCalendar>, Boolean> {
    var complete = true
    val calendars = DavResource(client, home).propfind(
        1, WebDAV.ResourceType, WebDAV.DisplayName, CalDAV.CalendarColor, CalDAV.SupportedCalendarComponentSet, WebDAV.CurrentUserPrivilegeSet,
    ).responses()
        .filter { r ->
            if (!r.isSuccess() || !r.error.isNullOrEmpty()) complete = false
            sameOrigin(r.href, home) &&
                r[ResourceType::class.java]?.types?.contains(CalDAV.Calendar) == true &&
                    // No component set means the calendar takes every kind.
                    r[SupportedCalendarComponentSet::class.java]?.supportsEvents != false
        }
        .map { r ->
            RemoteCalendar(
                url = r.href.withTrailingSlash(),
                name = r[DisplayName::class.java]?.displayName?.takeIf { it.isNotBlank() } ?: r.hrefName(),
                color = r[CalendarColor::class.java]?.color,
                writable = r[CurrentUserPrivilegeSet::class.java]?.mayWriteContent ?: true,
            )
        }
        .toList()
    return calendars to complete
}

/** DIRTY value for rows being uploaded; app writes set DIRTY to 1. */
private const val UPLOADING = 2

/** Accent blue, for calendars the server gives no color. */
private const val DEFAULT_COLOR = 0xFF346BF1.toInt()

/** Event columns read for upload; the iCal mapping uses the same names. */
private val EVENT_COLUMNS = arrayOf(
    Events._ID, Events.UID_2445, Events.TITLE, Events.EVENT_LOCATION, Events.DESCRIPTION, Events.DTSTART, Events.DTEND,
    Events.DURATION, Events.ALL_DAY, Events.EVENT_TIMEZONE, Events.RRULE, Events.EXRULE, Events.RDATE, Events.EXDATE, Events.STATUS,
    Events.ORIGINAL_INSTANCE_TIME, Events.DELETED, Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2,
)

/**
 * Two-way sync of one account's calendars with the provider. Calendars keep the collection URL in `_SYNC_ID` and the
 * ctag in `CAL_SYNC1`; series masters keep the resource name in `_SYNC_ID`, its etag in `SYNC_DATA1` and the iCalendar
 * last seen in `SYNC_DATA2`. Local changes are pushed first; on conflict the server wins.
 */
class CalDavSync(private val account: Account, private val provider: ContentProviderClient, private val client: HttpClient) {

    /** Series whose upload failed in this run; they stay dirty and go up on the next sync. */
    var failedUploads = 0
        private set

    private fun Uri.asSyncAdapter(): Uri = buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, account.name)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type)
        .build()

    private val calendarsUri = Calendars.CONTENT_URI.asSyncAdapter()
    private val eventsUri = Events.CONTENT_URI.asSyncAdapter()
    private val remindersUri = Reminders.CONTENT_URI.asSyncAdapter()
    private val attendeesUri = Attendees.CONTENT_URI.asSyncAdapter()

    suspend fun run(home: Url) {
        val (remote, complete) = remoteCalendars(client, home)
        val local = provider.query(
            calendarsUri, arrayOf(Calendars._ID, Calendars._SYNC_ID, Calendars.CAL_SYNC1),
            "${Calendars.ACCOUNT_NAME} = ? AND ${Calendars.ACCOUNT_TYPE} = ?", arrayOf(account.name, account.type), null,
        )?.use { c -> buildMap { while (c.moveToNext()) put(c.getString(1), c.getLong(0) to c.getString(2)) } }.orEmpty()
        // Calendars deleted on the server go, with their events, unless the listing may have left some out.
        if (complete) local.filterKeys { url -> remote.none { it.url.toString() == url } }
            .forEach { (_, v) -> provider.delete(ContentUris.withAppendedId(calendarsUri, v.first), null, null) }
        for (cal in remote) {
            val values = ContentValues().apply {
                put(Calendars.CALENDAR_DISPLAY_NAME, cal.name)
                put(Calendars.CALENDAR_COLOR, cal.color ?: DEFAULT_COLOR)
                put(Calendars.CALENDAR_ACCESS_LEVEL, if (cal.writable) Calendars.CAL_ACCESS_OWNER else Calendars.CAL_ACCESS_READ)
            }
            val known = local[cal.url.toString()]
            val id = if (known != null) {
                provider.update(ContentUris.withAppendedId(calendarsUri, known.first), values, null, null)
                known.first
            } else {
                values.apply {
                    put(Calendars.ACCOUNT_NAME, account.name)
                    put(Calendars.ACCOUNT_TYPE, account.type)
                    put(Calendars.OWNER_ACCOUNT, account.name)
                    put(Calendars.NAME, cal.url.toString())
                    put(Calendars._SYNC_ID, cal.url.toString())
                    put(Calendars.SYNC_EVENTS, 1)
                    put(Calendars.VISIBLE, 1)
                    put(Calendars.ALLOWED_REMINDERS, "${Reminders.METHOD_DEFAULT},${Reminders.METHOD_ALERT}")
                }
                ContentUris.parseId(provider.insert(calendarsUri, values)!!)
            }
            syncEvents(id, cal.url, known?.second)
        }
    }

    private suspend fun syncEvents(calendarId: Long, url: Url, ctag: String?) {
        val dav = DavCalendar(client, url)
        val pushed = push(calendarId, url)
        val newCtag = dav.propfind(0, CalDAV.GetCTag).responses().firstOrNull()?.get(GetCTag::class.java)?.cTag
        if (!pushed && newCtag != null && newCtag == ctag) return

        val remote = HashMap<String, String?>()
        // A failed or truncated listing (e.g. 507 on the collection) may leave out live events, so it deletes nothing.
        var complete = true
        dav.calendarQuery("VEVENT", null, null).responses().collect { r ->
            if (!r.isSuccess() || !r.error.isNullOrEmpty()) complete = false
            else if (r.href != url) remote[r.hrefName()] = r[GetETag::class.java]?.eTag
        }
        // A new series gets its name before its first upload; until one succeeds (SYNC_DATA2 set) it isn't the server's to delete.
        val local = provider.query(
            eventsUri, arrayOf(Events._ID, Events._SYNC_ID, Events.SYNC_DATA1),
            "${Events.CALENDAR_ID} = ? AND ${Events.ORIGINAL_ID} IS NULL AND ${Events._SYNC_ID} IS NOT NULL AND ${Events.SYNC_DATA2} IS NOT NULL",
            arrayOf("$calendarId"), null,
        )?.use { c -> buildMap { while (c.moveToNext()) put(c.getString(1), c.getLong(0) to c.getString(2)) } }.orEmpty()
        if (complete) local.filterKeys { it !in remote }.values.forEach { deleteSeries(it.first) }
        // Names not downloaded yet; any left over, or an incomplete listing, keep the old ctag, so the next sync fetches them again.
        val missing = remote.filter { (name, etag) -> etag == null || local[name]?.second != etag }.keys.toMutableSet()
        missing.toList().chunked(50).forEach { names ->
            dav.multiget(names.map { member(url, it) }).responses().collect { r ->
                val ics = r[CalendarData::class.java]?.iCalendar
                if (r.isSuccess() && ics != null) {
                    store(calendarId, r.hrefName(), r[GetETag::class.java]?.eTag, ics)
                    missing -= r.hrefName()
                }
            }
        }
        if (complete && missing.isEmpty()) provider.update(
            ContentUris.withAppendedId(calendarsUri, calendarId), ContentValues().apply { put(Calendars.CAL_SYNC1, newCtag) }, null, null,
        )
    }

    /** Uploads changed and deleted series. True if there were any. */
    private suspend fun push(calendarId: Long, url: Url): Boolean {
        val series = provider.query(
            eventsUri, arrayOf(Events._ID, Events.ORIGINAL_ID),
            "${Events.CALENDAR_ID} = ? AND (${Events.DIRTY} != 0 OR ${Events.DELETED} = 1)", arrayOf("$calendarId"), null,
        )?.use { c -> buildSet { while (c.moveToNext()) add(if (c.isNull(1)) c.getLong(0) else c.getLong(1)) } }.orEmpty()
        series.forEach {
            try {
                pushSeries(calendarId, url, it)
            } catch (e: UnauthorizedException) {
                throw e
            } catch (e: OriginRefusedException) {
                throw e
            } catch (e: Exception) {
                // One bad series shouldn't hold back the others or the pull; it stays dirty for the next sync.
                android.util.Log.w("CalDavSync", "Upload of event $it failed", e)
                failedUploads++
            }
        }
        return series.isNotEmpty()
    }

    private suspend fun pushSeries(calendarId: Long, url: Url, id: Long) {
        // Mark the series as uploading before reading it. An app edit during the upload sets DIRTY back to 1, which the
        // guarded clear below leaves alone, so the edit goes up on the next sync.
        provider.update(
            eventsUri, ContentValues().apply { put(Events.DIRTY, UPLOADING) },
            "(${Events._ID} = ? OR ${Events.ORIGINAL_ID} = ?) AND ${Events.DIRTY} = 1", arrayOf("$id", "$id"),
        )
        val master = rows("${Events._ID} = ?", id).firstOrNull() ?: return
        val name = master.string(Events._SYNC_ID)
        val etag = master.string(Events.SYNC_DATA1)
        val ifMatch = etag?.let { headersOf(HttpHeaders.IfMatch, QuotedStringUtils.asQuotedString(it)) }
        if (master.long(Events.DELETED) == 1L) {
            if (name != null) try {
                DavResource(client, member(url, name)).delete(ifMatch) {}
            } catch (_: NotFoundException) {
            } catch (_: PreconditionFailedException) {
                // Changed on the server since: the next pull brings the server's copy back.
            }
            deleteSeries(id)
            return
        }
        val exceptions = rows("${Events.ORIGINAL_ID} = ?", id)
        exceptions.filter { it.long(Events.DELETED) == 1L }.forEach { provider.delete(ContentUris.withAppendedId(eventsUri, it.long(Events._ID)!!), null, null) }
        val live = exceptions.filter { it.long(Events.DELETED) != 1L }
        val uid = master.string(Events.UID_2445) ?: UUID.randomUUID().toString()
        val file = name ?: "$uid.ics"
        // Saved before the PUT, so a retry after a lost response reuses them and gets a 412 instead of making a duplicate.
        if (name == null) provider.update(
            ContentUris.withAppendedId(eventsUri, id), ContentValues().apply {
                put(Events._SYNC_ID, file)
                put(Events.UID_2445, uid)
            }, null, null,
        )
        val ics = toICal(master.copy(values = master.values + (Events.UID_2445 to uid)), live, master.string(Events.SYNC_DATA2))
        val newEtag = try {
            DavResource(client, member(url, file)).put(
                TextContent(ics, DavCalendar.MIME_ICALENDAR_UTF8),
                // Never uploaded successfully (no SYNC_DATA2): create only, never overwrite.
                if (master.string(Events.SYNC_DATA2) == null) headersOf(HttpHeaders.IfNoneMatch, "*") else ifMatch,
            ) { GetETag.fromHttpResponse(it)?.eTag }
        } catch (_: PreconditionFailedException) {
            // Server wins: replace the local change with the server's copy, or drop it if the server deleted it.
            // The row already has _SYNC_ID = file, so store() overwrites it in place, even if it has no etag yet.
            download(calendarId, url, file, id)
            return
        }
        val ops = arrayListOf(
            ContentProviderOperation.newUpdate(ContentUris.withAppendedId(eventsUri, id))
                .withValue(Events._SYNC_ID, file)
                .withValue(Events.UID_2445, uid)
                .withValue(Events.SYNC_DATA1, newEtag)
                .withValue(Events.SYNC_DATA2, ics)
                .build(),
        )
        live.forEach {
            ops += ContentProviderOperation.newUpdate(ContentUris.withAppendedId(eventsUri, it.long(Events._ID)!!))
                .withValue(Events.ORIGINAL_SYNC_ID, file)
                .build()
        }
        // Only rows still marked UPLOADING are clean. The provider rejects a selection on an id URI, hence _ID in it.
        ops += ContentProviderOperation.newUpdate(eventsUri)
            .withValue(Events.DIRTY, 0)
            .withSelection("(${Events._ID} = ? OR ${Events.ORIGINAL_ID} = ?) AND ${Events.DIRTY} = $UPLOADING", arrayOf("$id", "$id"))
            .build()
        provider.applyBatch(ops)
    }

    private suspend fun download(calendarId: Long, url: Url, file: String, id: Long) {
        val got = try {
            DavResource(client, member(url, file)).get(headersOf(HttpHeaders.Accept, "text/calendar")) { r ->
                GetETag.fromHttpResponse(r)?.eTag to r.bodyAsText()
            }
        } catch (_: NotFoundException) {
            null
        }
        // store() overwrites the master in place (clearing DIRTY) and replaces its exceptions.
        if (got == null) deleteSeries(id) else store(calendarId, file, got.first, got.second, force = true)
    }

    /** Provider rows matching [selection] (with [id] as its argument), with their alert reminders. */
    private fun rows(selection: String, id: Long): List<EventRow> =
        provider.query(eventsUri, EVENT_COLUMNS, selection, arrayOf("$id"), null)?.use { c ->
            buildList { while (c.moveToNext()) add(EventRow(c.toMap(), reminders(c.getLong(0)))) }
        }.orEmpty()

    private fun reminders(eventId: Long): List<Int> = provider.query(
        remindersUri, arrayOf(Reminders.MINUTES),
        "${Reminders.EVENT_ID} = ? AND ${Reminders.METHOD} IN (${Reminders.METHOD_DEFAULT}, ${Reminders.METHOD_ALERT})", arrayOf("$eventId"), null,
    )?.use { c -> buildList { while (c.moveToNext()) add(c.getInt(0)) } }.orEmpty().distinct().sorted()

    /**
     * Replaces the local copy of resource [name] with [ics]; the master row is updated in place to keep its id. Unless
     * [force], a series with local changes is left alone: the next push uploads them, or gets a 412 and forces this.
     */
    private fun store(calendarId: Long, name: String, etag: String?, ics: String, force: Boolean = false) {
        // shortcut: resources ical4j can't parse are skipped, so one bad event doesn't stop the sync. Log them if it matters.
        val rows = runCatching { parseEvents(ics) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return
        val existing = provider.query(
            eventsUri, arrayOf(Events._ID),
            "${Events.CALENDAR_ID} = ? AND ${Events._SYNC_ID} = ? AND ${Events.ORIGINAL_ID} IS NULL", arrayOf("$calendarId", name), null,
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }
        val ops = ArrayList<ContentProviderOperation>()
        val master = rows.first()
        val values = (master.values + mapOf(
            Events.CALENDAR_ID to calendarId, Events._SYNC_ID to name, Events.SYNC_DATA1 to etag, Events.SYNC_DATA2 to ics, Events.DIRTY to 0,
        )).toValues()
        fun reminders(row: EventRow, ref: (ContentProviderOperation.Builder) -> ContentProviderOperation.Builder) {
            row.reminders.forEach {
                ops += ref(ContentProviderOperation.newInsert(remindersUri))
                    .withValue(Reminders.MINUTES, it)
                    .withValue(Reminders.METHOD, Reminders.METHOD_ALERT)
                    .build()
            }
            row.attendees.forEach { ops += ref(ContentProviderOperation.newInsert(attendeesUri)).withValues(it.toValues()).build() }
        }
        val masterRef: (ContentProviderOperation.Builder, String) -> ContentProviderOperation.Builder
        if (existing != null) {
            if (!force) ops += ContentProviderOperation.newAssertQuery(eventsUri)
                .withSelection("(${Events._ID} = ? OR ${Events.ORIGINAL_ID} = ?) AND ${Events.DIRTY} != 0", arrayOf("$existing", "$existing"))
                .withExpectedCount(0)
                .build()
            // The provider unboxes STATUS on update and throws on null (seen on API 36); a non-cancelled value
            // replaces a CANCELLED one left from before.
            if (master[Events.STATUS] == null) values.put(Events.STATUS, Events.STATUS_CONFIRMED)
            ops += ContentProviderOperation.newUpdate(ContentUris.withAppendedId(eventsUri, existing)).withValues(values).build()
            ops += ContentProviderOperation.newDelete(remindersUri).withSelection("${Reminders.EVENT_ID} = ?", arrayOf("$existing")).build()
            ops += ContentProviderOperation.newDelete(attendeesUri).withSelection("${Attendees.EVENT_ID} = ?", arrayOf("$existing")).build()
            ops += ContentProviderOperation.newDelete(eventsUri).withSelection("${Events.ORIGINAL_ID} = ?", arrayOf("$existing")).build()
            masterRef = { b, column -> b.withValue(column, existing) }
        } else {
            val at = ops.size
            ops += ContentProviderOperation.newInsert(eventsUri).withValues(values).build()
            masterRef = { b, column -> b.withValueBackReference(column, at) }
        }
        reminders(master) { masterRef(it, Reminders.EVENT_ID) } // Attendees.EVENT_ID is the same "event_id" column
        rows.drop(1).forEach { row ->
            val at = ops.size
            ops += masterRef(ContentProviderOperation.newInsert(eventsUri), Events.ORIGINAL_ID)
                .withValues((row.values + mapOf(Events.CALENDAR_ID to calendarId, Events.ORIGINAL_SYNC_ID to name, Events.DIRTY to 0)).toValues())
                .build()
            reminders(row) { it.withValueBackReference(Reminders.EVENT_ID, at) }
        }
        try {
            provider.applyBatch(ops)
        } catch (e: OperationApplicationException) {
            // The assert failed: edited locally since the push, so keep the edit.
            if (existing == null || force) throw e
        }
    }

    /** Removes a series' rows for good (sync adapter deletes skip the DELETED flag). */
    private fun deleteSeries(id: Long) {
        provider.delete(eventsUri, "${Events._ID} = ? OR ${Events.ORIGINAL_ID} = ?", arrayOf("$id", "$id"))
    }
}

private fun member(collection: Url, name: String) = URLBuilder(collection).appendPathSegments(name).build()

private fun Cursor.toMap(): Map<String, Any?> = columnNames.withIndex().associate { (i, name) ->
    name to when (getType(i)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> getLong(i)
        else -> getString(i)
    }
}

private fun Map<String, Any?>.toValues() = ContentValues().apply {
    forEach { (k, v) ->
        when (v) {
            null -> putNull(k)
            is Int -> put(k, v)
            is Long -> put(k, v)
            else -> put(k, v.toString())
        }
    }
}
