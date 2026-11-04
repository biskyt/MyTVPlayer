package com.biskyt.mytvplayer

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.LocaleList
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.biskyt.mytvplayer.core.net.IpLookup
import com.biskyt.mytvplayer.core.net.ProxyClients
import com.biskyt.mytvplayer.core.sync.DriveStore
import com.biskyt.mytvplayer.core.sync.GoogleAuth
import com.biskyt.mytvplayer.core.sync.SyncException
import com.biskyt.mytvplayer.data.BackupFile
import com.biskyt.mytvplayer.data.BackupRepository
import com.biskyt.mytvplayer.data.CatchupRepository
import com.biskyt.mytvplayer.data.CategoryStore
import com.biskyt.mytvplayer.data.ChannelRepository
import com.biskyt.mytvplayer.data.CrashLog
import com.biskyt.mytvplayer.data.EpgRepository
import com.biskyt.mytvplayer.data.ExternalIp
import com.biskyt.mytvplayer.data.Favourites
import com.biskyt.mytvplayer.data.ParentalRepository
import com.biskyt.mytvplayer.data.PlaylistRepository
import com.biskyt.mytvplayer.data.ProgressSync
import com.biskyt.mytvplayer.data.SearchRepository
import com.biskyt.mytvplayer.data.SettingsRepository
import com.biskyt.mytvplayer.data.SyncAccount
import com.biskyt.mytvplayer.data.SyncManager
import com.biskyt.mytvplayer.data.VodRepository
import com.biskyt.mytvplayer.data.VpnMonitor
import com.biskyt.mytvplayer.data.dataStore
import com.biskyt.mytvplayer.data.db.AppDatabase
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient

/**
 * How the app names itself to servers. Some hosts (Wikimedia, archive.org) refuse or throttle clients that don't say
 * who they are and how to reach their maker. Sent with downloads, pictures and streams, except a stream whose M3U entry
 * gives its own (`http-user-agent`).
 */
val USER_AGENT = "MyTVPlayer/${BuildConfig.VERSION_NAME} (+https://github.com/biskyt/MyTVPlayer)"

/** Background work that needs the network waits until the TV is online. */
private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

/** Hand-wired dependencies: small enough that a DI framework would only add start-up time. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDatabase = AppDatabase.create(context)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // Downloads and pictures (logos, posters) say who's asking, unless a request already does.
        .addInterceptor { chain ->
            val r = chain.request()
            chain.proceed(if (r.header("User-Agent") == null) r.newBuilder().header("User-Agent", USER_AGENT).build() else r)
        }
        .build()
    val settings = SettingsRepository(context)
    val parental = ParentalRepository(db, settings, appScope)

    /** One client for each playlist proxy: the playlists, their guides and the player share them. */
    val proxies = ProxyClients(http)

    /** The same for the player's streams: shorter waits than the downloads', so a stall is found sooner. */
    val playerProxies = ProxyClients(http.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build())
    val epg = EpgRepository(db, http, context.filesDir, appScope, proxies = proxies)
    private val categoryStore = CategoryStore(db)
    val playlists = PlaylistRepository(db, http, settings, epg, context.cacheDir.resolve("playlists"), categoryStore, proxies)
    val channels = ChannelRepository(db)
    val progressSync = ProgressSync(
        db,
        SyncAccount(context.dataStore),
        BuildConfig.GOOGLE_CLIENT_ID.takeIf { it.isNotBlank() }?.let { GoogleAuth(http, it, BuildConfig.GOOGLE_CLIENT_SECRET) },
        appScope,
        { token -> DriveStore(http, token) }
    )
    val vod = VodRepository(db, playlists, categoryStore, progressSync)
    val favourites = Favourites(appScope, settings, channels, vod)
    val search = SearchRepository(db)
    val catchup = CatchupRepository(playlists)
    val sync = SyncManager(appScope, playlists, vod, epg, settings)
    val vpn = VpnMonitor(context, appScope)

    // No pooled connection: one opened before a VPN started would still show the address without it.
    val externalIp =
        ExternalIp(
            vpn.network,
            IpLookup(http.newBuilder().connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS)).build())::lookup,
            appScope
        )
    val backup = BackupRepository(db, settings, epg, sync)
    val backupFile = BackupFile(context)
    val crashLog = CrashLog(context.filesDir, BuildConfig.VERSION_NAME)

    /** A backup file another app (e.g. a file manager's "Open with") handed to MyTVPlayer, until it's offered to restore. */
    val openedFile = MutableStateFlow<Uri?>(null)
}

open class MyTvApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // First, so a crash while setting up the rest is recorded too.
        installCrashLog()
        AppText.init(this)
        deviceLocales = resources.configuration.locales
        container = AppContainer(this)
        scheduleDailyRefresh()
    }

    // The device's language changed: words from AppText follow it (unless a language is chosen in Settings).
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.locales != deviceLocales) AppText.init(this)
        deviceLocales = newConfig.locales
    }

    private var deviceLocales: LocaleList? = null

    protected open fun installCrashLog() = CrashLog(filesDir, BuildConfig.VERSION_NAME).install()

    protected open fun scheduleDailyRefresh() {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(1, TimeUnit.DAYS).setConstraints(online).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("daily-refresh", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Channel logos: decoded at row size, small memory cache, disk cache capped so storage stays low. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.12).build() }
        .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("logos")).maxSizeBytes(LOGO_CACHE_BYTES).build() }
        .crossfade(false)
        .build()

    companion object {
        const val LOGO_CACHE_BYTES = 30L * 1024 * 1024
    }
}

val Context.container: AppContainer get() = (applicationContext as MyTvApp).container

/**
 * Finishes the sync between TVs after the user leaves the app with changes not sent yet (see ProgressSync), so they do
 * not wait for the next time the app opens. It waits until the TV is online.
 */
class ProgressSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        applicationContext.container.progressSync.finish()
        Result.success()
    } catch (e: SyncException) {
        // A few tries, for a connection that comes and goes; the next time the app opens syncs anyway.
        if (e.reason == SyncException.Reason.SIGNED_OUT || runAttemptCount >= TRIES) Result.success() else Result.retry()
    }

    companion object {
        private const val TRIES = 4

        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<ProgressSyncWorker>().setConstraints(online).build()
            // One at a time: if one waits already, it sends these changes too.
            WorkManager.getInstance(context).enqueueUniqueWork("progress-sync", ExistingWorkPolicy.KEEP, request)
        }
    }
}

/** Daily refresh of the playlist in use: its channels, films and series, and its TV guide. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        c.sync.refreshAllNow()
        // Storage housekeeping: everything else (logos, programmes, recents) is size- or age-limited as it is written.
        c.epg.deleteOrphanFiles()
        c.db.compactIfWorthwhile()
        return Result.success()
    }
}
