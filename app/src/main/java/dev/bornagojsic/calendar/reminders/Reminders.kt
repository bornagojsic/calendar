package dev.bornagojsic.calendar.reminders

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.CalendarContract
import android.provider.CalendarContract.CalendarAlerts
import android.provider.CalendarContract.Instances
import android.text.format.DateUtils
import dev.bornagojsic.calendar.MainActivity
import dev.bornagojsic.calendar.R
import dev.bornagojsic.calendar.SNOOZE_MINUTES
import dev.bornagojsic.calendar.domain.Prefs
import dev.bornagojsic.calendar.settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

private const val CHANNEL = "reminders"
private const val ACTION_SNOOZE = "dev.bornagojsic.calendar.SNOOZE"
private const val ACTION_SNOOZED = "dev.bornagojsic.calendar.SNOOZED"
const val EXTRA_EVENT_ID = "event_id"
const val EXTRA_BEGIN = "begin"
private const val EXTRA_END = "end"
private const val EXTRA_TITLE = "title"
private const val EXTRA_ALL_DAY = "all_day"
private const val EXTRA_LOCATION = "location"
private const val EXTRA_SNOOZE = "snooze_minutes"

/** The snooze action's label, e.g. "Snooze 15m" or "Snooze 1h". */
fun snoozeLabel(minutes: Int) = "Snooze " + if (minutes % 60 == 0) "${minutes / 60}h" else "${minutes}m"

/** One occurrence to remind about; also the payload carried through a snooze. */
private data class Alert(
    val eventId: Long,
    val begin: Long,
    val end: Long,
    val title: String,
    val allDay: Boolean,
    val location: String,
) {
    val tag get() = "$eventId-$begin"

    fun into(i: Intent) = i.putExtra(EXTRA_EVENT_ID, eventId).putExtra(EXTRA_BEGIN, begin).putExtra(EXTRA_END, end)
        .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_ALL_DAY, allDay).putExtra(EXTRA_LOCATION, location)

    companion object {
        fun from(i: Intent) = Alert(
            i.getLongExtra(EXTRA_EVENT_ID, -1), i.getLongExtra(EXTRA_BEGIN, 0), i.getLongExtra(EXTRA_END, 0),
            i.getStringExtra(EXTRA_TITLE).orEmpty(), i.getBooleanExtra(EXTRA_ALL_DAY, false), i.getStringExtra(EXTRA_LOCATION).orEmpty(),
        )
    }
}

/** Receives the provider's EVENT_REMINDER broadcast and posts a notification for each alert that is due. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CalendarContract.ACTION_EVENT_REMINDER) return
        val pending = goAsync()
        thread {
            try {
                fireDue(context)
            } catch (_: SecurityException) {
                // Calendar access was revoked: nothing to read, and a crash here would show a dialog.
            } finally {
                pending.finish()
            }
        }
    }

    /** Every scheduled alert at or before now, so alerts missed while the phone was off still show. */
    private fun fireDue(context: Context) {
        val resolver = context.contentResolver
        val projection = arrayOf(
            CalendarAlerts._ID, CalendarAlerts.EVENT_ID, CalendarAlerts.BEGIN, CalendarAlerts.END,
            CalendarAlerts.TITLE, CalendarAlerts.ALL_DAY, CalendarAlerts.EVENT_LOCATION,
        )
        val where = "${CalendarAlerts.STATE} = ${CalendarAlerts.STATE_SCHEDULED} AND ${CalendarAlerts.ALARM_TIME} <= ?"
        val fired = ContentValues().apply { put(CalendarAlerts.STATE, CalendarAlerts.STATE_FIRED) }
        resolver.query(CalendarAlerts.CONTENT_URI, projection, where, arrayOf("${System.currentTimeMillis()}"), null)?.use { c ->
            while (c.moveToNext()) {
                notify(context, Alert(c.getLong(1), c.getLong(2), c.getLong(3), c.getString(4).orEmpty(), c.getInt(5) != 0, c.getString(6).orEmpty()))
                resolver.update(ContentUris.withAppendedId(CalendarAlerts.CONTENT_URI, c.getLong(0)), fired, null, null)
            }
        }
    }
}

/** Our own snooze actions; not exported, so other apps can't post reminders through it. */
class SnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alert = Alert.from(intent)
        when (intent.action) {
            ACTION_SNOOZE -> {
                context.getSystemService(NotificationManager::class.java).cancel(alert.tag, 0)
                // shortcut: AlarmManager alarms don't survive a reboot, so a snooze pending at shutdown is lost.
                val am = context.getSystemService(AlarmManager::class.java)
                val at = System.currentTimeMillis() + intent.getIntExtra(EXTRA_SNOOZE, Prefs().snoozeMinutes) * 60_000L
                val fire = broadcast(context, ACTION_SNOOZED, alert)
                if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire)
                }
            }
            ACTION_SNOOZED -> {
                val pending = goAsync()
                thread {
                    try {
                        current(context, alert)?.let { notify(context, it) }
                    } catch (_: SecurityException) {
                        // Calendar access was revoked: nothing to read, and a crash here would show a dialog.
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}

/** The occurrence as it is now, or null if it was deleted or moved since the snooze. */
private fun current(context: Context, alert: Alert): Alert? {
    val uri = Instances.CONTENT_URI.buildUpon().also {
        ContentUris.appendId(it, alert.begin)
        ContentUris.appendId(it, alert.begin)
    }.build()
    val projection = arrayOf(Instances.END, Instances.TITLE, Instances.ALL_DAY, Instances.EVENT_LOCATION)
    val where = "${Instances.EVENT_ID} = ? AND ${Instances.BEGIN} = ?"
    return context.contentResolver.query(uri, projection, where, arrayOf("${alert.eventId}", "${alert.begin}"), null)?.use { c ->
        if (c.moveToFirst()) alert.copy(end = c.getLong(0), title = c.getString(1).orEmpty(), allDay = c.getInt(2) != 0, location = c.getString(3).orEmpty()) else null
    }
}

private fun broadcast(context: Context, action: String, alert: Alert, snoozeMinutes: Int = 0): PendingIntent {
    val intent = alert.into(Intent(action).setClass(context, SnoozeReceiver::class.java)).putExtra(EXTRA_SNOOZE, snoozeMinutes)
    // Unique per occurrence and action, so snoozes of different events don't replace each other.
    return PendingIntent.getBroadcast(context, (alert.tag + action).hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

private fun notify(context: Context, alert: Alert) {
    val nm = context.getSystemService(NotificationManager::class.java)
    if (!nm.areNotificationsEnabled()) return
    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH))
    val open = Intent(context, MainActivity::class.java)
        .putExtra(EXTRA_EVENT_ID, alert.eventId)
        .putExtra(EXTRA_BEGIN, alert.begin)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val tap = PendingIntent.getActivity(context, alert.tag.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    // All-day events sit on UTC midnights; the end is exclusive, which formatDateRange expects.
    val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL or
        if (alert.allDay) DateUtils.FORMAT_UTC else DateUtils.FORMAT_SHOW_TIME
    val time = DateUtils.formatDateRange(context, alert.begin, alert.end, flags)
    val text = if (alert.location.isBlank()) time else "$time · ${alert.location}"
    // Fixed per notification, so the label and the actual snooze always match. Called off the main thread.
    val snooze = runBlocking { context.settings.data.first()[SNOOZE_MINUTES] } ?: Prefs().snoozeMinutes
    val n = Notification.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(alert.title.ifBlank { "(No title)" })
        .setContentText(text)
        .setCategory(Notification.CATEGORY_EVENT)
        .setContentIntent(tap)
        .setAutoCancel(true)
        .addAction(Notification.Action.Builder(null, snoozeLabel(snooze), broadcast(context, ACTION_SNOOZE, alert, snooze)).build())
        .build()
    nm.notify(alert.tag, 0, n)
}
