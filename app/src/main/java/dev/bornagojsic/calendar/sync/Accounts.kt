package dev.bornagojsic.calendar.sync

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.accounts.OnAccountsUpdateListener
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import at.bitfire.dav4jvm.ktor.exception.UnauthorizedException
import io.ktor.http.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

const val ACCOUNT_TYPE = "dev.bornagojsic.calendar"
private const val KEY_HOME = "home"
private const val KEY_USER = "user"

/** CalDAV accounts this app owns, registered with Android and synced through [SyncAdapter]. */
class CalDavAccounts(private val context: Context) {
    private val manager = AccountManager.get(context)

    /** The app's accounts now and after every change. */
    fun accounts(): Flow<List<Account>> = callbackFlow {
        val listener = OnAccountsUpdateListener { all -> trySend(all.filter { it.type == ACCOUNT_TYPE }) }
        manager.addOnAccountsUpdatedListener(listener, null, true, arrayOf(ACCOUNT_TYPE))
        awaitClose { manager.removeOnAccountsUpdatedListener(listener) }
    }

    /** Checks the login against the server, then adds the account and syncs it. Throws with a message for the user. */
    suspend fun add(url: String, user: String, password: String, syncMinutes: Int) = withContext(Dispatchers.IO) {
        val base = Url(if ("://" in url) url.trim() else "https://${url.trim()}")
        val home = davClient(user, password, base).use { client ->
            try {
                findHome(client, base).also { remoteCalendars(client, it) }
            } catch (_: UnauthorizedException) {
                throw IllegalArgumentException("Wrong username or password")
            }
        }
        val account = Account("$user@${base.host}", ACCOUNT_TYPE)
        val data = Bundle().apply {
            putString(KEY_HOME, home.toString())
            putString(KEY_USER, user)
        }
        require(manager.addAccountExplicitly(account, password, data)) { "${account.name} is already added" }
        ContentResolver.setIsSyncable(account, CalendarContract.AUTHORITY, 1)
        ContentResolver.setSyncAutomatically(account, CalendarContract.AUTHORITY, true)
        ContentResolver.addPeriodicSync(account, CalendarContract.AUTHORITY, Bundle.EMPTY, syncMinutes * 60L)
        sync(account)
    }

    /** Removes the account and its calendars from the phone; the server keeps everything. */
    suspend fun remove(account: Account) = withContext(Dispatchers.IO) {
        val uri = Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, account.name)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type)
            .build()
        context.contentResolver.delete(uri, "${Calendars.ACCOUNT_NAME} = ? AND ${Calendars.ACCOUNT_TYPE} = ?", arrayOf(account.name, account.type))
        manager.removeAccountExplicitly(account)
    }

    fun setSyncMinutes(minutes: Int) = mine().forEach {
        ContentResolver.addPeriodicSync(it, CalendarContract.AUTHORITY, Bundle.EMPTY, minutes * 60L)
    }

    /** Uploads local edits right away instead of waiting for the next periodic sync. */
    fun upload() = mine().forEach { sync(it, ContentResolver.SYNC_EXTRAS_UPLOAD) }

    /** Syncs every calendar account on the phone (DAVx5 and Google too) and returns once it's done, or after a minute. */
    suspend fun refresh() {
        ContentResolver.requestSync(null, CalendarContract.AUTHORITY, extras(ContentResolver.SYNC_EXTRAS_MANUAL))
        val active = callbackFlow {
            fun check() = trySend(ContentResolver.getCurrentSyncs().any { it.authority == CalendarContract.AUTHORITY })
            val handle = ContentResolver.addStatusChangeListener(ContentResolver.SYNC_OBSERVER_TYPE_ACTIVE) { check() }
            check()
            awaitClose { ContentResolver.removeStatusChangeListener(handle) }
        }
        // The framework may take a moment to start the sync, or skip it (no accounts, auto-sync off).
        withTimeoutOrNull(60_000) {
            if (withTimeoutOrNull(5_000) { active.first { it } } != null) active.first { !it }
        }
    }

    private fun mine() = manager.getAccountsByType(ACCOUNT_TYPE).toList()

    private fun sync(account: Account, flag: String = ContentResolver.SYNC_EXTRAS_MANUAL) =
        ContentResolver.requestSync(account, CalendarContract.AUTHORITY, extras(flag))

    private fun extras(flag: String) = Bundle().apply {
        putBoolean(flag, true)
        putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
    }
}

/** Runs [CalDavSync] for one of the app's accounts when Android schedules a calendar sync. */
class SyncAdapter(context: Context) : AbstractThreadedSyncAdapter(context, false) {
    override fun onPerformSync(account: Account, extras: Bundle, authority: String, provider: ContentProviderClient, result: SyncResult) {
        val manager = AccountManager.get(context)
        val home = manager.getUserData(account, KEY_HOME) ?: return
        try {
            davClient(manager.getUserData(account, KEY_USER), manager.getPassword(account), Url(home)).use { client ->
                runBlocking { CalDavSync(account, provider, client).run(Url(home)) }
            }
        } catch (_: UnauthorizedException) {
            result.stats.numAuthExceptions++
        } catch (e: Exception) {
            // Network, server and provider errors: Android retries with backoff.
            android.util.Log.w("CalDavSync", "Sync of ${account.name} failed", e)
            result.stats.numIoExceptions++
        }
    }
}

class SyncService : Service() {
    private val adapter by lazy { SyncAdapter(applicationContext) }
    override fun onBind(intent: Intent?) = adapter.syncAdapterBinder
}

/** Accounts are added and removed in the app's Settings; Android's own account screens only list them. */
private class Authenticator(private val context: Context) : AbstractAccountAuthenticator(context) {
    override fun addAccount(r: AccountAuthenticatorResponse?, type: String?, token: String?, features: Array<out String>?, options: Bundle?) =
        Bundle().apply { putParcelable(AccountManager.KEY_INTENT, Intent(context, dev.bornagojsic.calendar.MainActivity::class.java)) }
    override fun editProperties(r: AccountAuthenticatorResponse?, type: String?) = null
    override fun confirmCredentials(r: AccountAuthenticatorResponse?, account: Account?, options: Bundle?) = null
    override fun getAuthToken(r: AccountAuthenticatorResponse?, account: Account?, type: String?, options: Bundle?) = null
    override fun getAuthTokenLabel(type: String?) = null
    override fun updateCredentials(r: AccountAuthenticatorResponse?, account: Account?, type: String?, options: Bundle?) = null
    override fun hasFeatures(r: AccountAuthenticatorResponse?, account: Account?, features: Array<out String>?) = null
}

class AuthenticatorService : Service() {
    private val authenticator by lazy { Authenticator(this) }
    override fun onBind(intent: Intent?) = authenticator.iBinder
}
