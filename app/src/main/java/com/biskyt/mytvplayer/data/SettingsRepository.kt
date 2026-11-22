package com.biskyt.mytvplayer.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.biskyt.mytvplayer.core.player.SubtitleMode
import com.biskyt.mytvplayer.core.ui.AccentPalette
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

enum class BufferSize { SMALL, NORMAL, LARGE }

enum class StreamFormat { TS, HLS }

/** Serializable for backups (see BackupRepository). */
@Serializable
data class Settings(
    val activePlaylistId: Long = 0,
    val skipBackSec: Int = 10,
    val skipForwardSec: Int = 30,
    val audioPassthrough: Boolean = true,
    /** Decoded AAC surround sound is sent as Dolby Digital 5.1 (with passthrough on; see DolbyDigitalSink). */
    val surroundAsDolby: Boolean = false,
    /** The user's TMDB key (v3 key or v4 token); blank: TMDB is off. In backups, but not synced between TVs. */
    val tmdbKey: String = "",
    val subtitleMode: SubtitleMode = SubtitleMode.FORCED_ONLY,
    /** Empty means "follow the TV's system language". */
    val subtitleLanguage: String = "",
    val bufferSize: BufferSize = BufferSize.NORMAL,
    val streamFormat: StreamFormat = StreamFormat.TS,
    val startOnLastChannel: Boolean = false,
    /** Up and down on the remote change channel while watching (else only the channel buttons do). */
    val zapWithUpDown: Boolean = false,
    val lastChannelId: Long = 0,
    val accent: Int = AccentPalette.DEFAULT,
    val largeText: Boolean = false,
    /** Show in the top bar whether Android reports a VPN (see VpnMonitor). */
    val warnVpn: Boolean = false,
    /** With [warnVpn]: also show the public IP address (see ExternalIp). It asks ipinfo.io, so it is off by default. */
    val showExternalIp: Boolean = false,
    /**
     * Show whether the active playlist's proxy works, and its address (see ProxyMonitor). It asks ipinfo.io, so it is off
     * by default.
     */
    val warnProxy: Boolean = false,
    /** Download in the options of films and episodes (see Downloads); off by default (the owner, 2026-11-22). */
    val downloads: Boolean = false,
    /** Continue watching and the favourite channels on the TV's home screen (see LauncherSync), the owner 2026-11-14. */
    val launcherRows: Boolean = false,
    /** Parental controls: leave restricted categories out of these (see ParentalRepository). */
    val hideRestrictedRecent: Boolean = true,
    val hideRestrictedCarryOn: Boolean = true,
    val hideRestrictedSearch: Boolean = true,
    /** The parental controls PIN, salted and hashed (see Pin); empty when none is set. */
    val pinHash: String = ""
) {
    val effectiveSubtitleLanguage: String get() = subtitleLanguage.ifEmpty { Locale.getDefault().language }
}

internal val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    private object K {
        val activePlaylist = longPreferencesKey("active_playlist")
        val skipBack = intPreferencesKey("skip_back")
        val skipForward = intPreferencesKey("skip_forward")
        val passthrough = booleanPreferencesKey("audio_passthrough")
        val surroundAsDolby = booleanPreferencesKey("surround_as_dolby")
        val tmdbKey = stringPreferencesKey("tmdb_key")
        val aacUnchanged = booleanPreferencesKey("aac_unchanged")
        val aacInApp = booleanPreferencesKey("aac_in_app")
        val subtitleMode = stringPreferencesKey("subtitle_mode")
        val subtitleLang = stringPreferencesKey("subtitle_lang")
        val buffer = stringPreferencesKey("buffer")
        val format = stringPreferencesKey("stream_format")
        val startLast = booleanPreferencesKey("start_last")
        val zapUpDown = booleanPreferencesKey("zap_up_down")
        val lastChannel = longPreferencesKey("last_channel")
        val accent = intPreferencesKey("accent")
        val largeText = booleanPreferencesKey("large_text")
        val warnVpn = booleanPreferencesKey("warn_vpn")
        val warnProxy = booleanPreferencesKey("warn_proxy")
        val downloads = booleanPreferencesKey("downloads")
        val launcherRows = booleanPreferencesKey("launcher_rows")
        val showIp = booleanPreferencesKey("show_external_ip")
        val hideRecent = booleanPreferencesKey("restricted_hide_recent")
        val hideCarryOn = booleanPreferencesKey("restricted_hide_carry_on")
        val hideSearch = booleanPreferencesKey("restricted_hide_search")
        val pinHash = stringPreferencesKey("pin_hash")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { it.toSettings() }.distinctUntilChanged()

    suspend fun current(): Settings = settings.first()

    /** The playlist in use; 0 (none yet) is skipped so screens never query for it. */
    val activePlaylistId: Flow<Long> = settings.map { it.activePlaylistId }.filter { it != 0L }.distinctUntilChanged()

    /**
     * The live stream format that worked for [playlistId]'s provider when the one set didn't (see PlayerViewModel);
     * null when there's none. One short value per provider, forgotten when the setting is changed (see [setStreamFormat]).
     */
    suspend fun streamFormatFor(playlistId: Long): StreamFormat? =
        enumOrNull<StreamFormat>(context.dataStore.data.first()[formatKey(playlistId)])

    suspend fun rememberStreamFormat(playlistId: Long, format: StreamFormat) {
        context.dataStore.edit { it[formatKey(playlistId)] = format.name }
    }

    /** Choosing a format starts afresh: what each provider fell back to is forgotten. */
    suspend fun setStreamFormat(format: StreamFormat) {
        context.dataStore.edit { prefs ->
            prefs[K.format] = format.name
            prefs.asMap().keys.filter { it.name.startsWith(FORMAT_FOR) }.forEach { prefs.remove(it) }
        }
    }

    suspend fun forgetStreamFormat(playlistId: Long) {
        context.dataStore.edit { it.remove(formatKey(playlistId)) }
    }

    private fun formatKey(playlistId: Long) = stringPreferencesKey("$FORMAT_FOR$playlistId")

    /** The value saved for [key], or [default] when none is saved. */
    private fun <T> Preferences.valueOr(key: Preferences.Key<T>, default: T): T = this[key] ?: default

    private fun Preferences.toSettings(): Settings {
        val d = Settings()
        return Settings(
            activePlaylistId = valueOr(K.activePlaylist, d.activePlaylistId),
            skipBackSec = valueOr(K.skipBack, d.skipBackSec),
            skipForwardSec = valueOr(K.skipForward, d.skipForwardSec),
            audioPassthrough = valueOr(K.passthrough, d.audioPassthrough),
            surroundAsDolby = valueOr(K.surroundAsDolby, d.surroundAsDolby),
            tmdbKey = valueOr(K.tmdbKey, d.tmdbKey),
            subtitleMode = enumOr(this[K.subtitleMode], d.subtitleMode),
            subtitleLanguage = valueOr(K.subtitleLang, d.subtitleLanguage),
            bufferSize = enumOr(this[K.buffer], d.bufferSize),
            streamFormat = enumOr(this[K.format], d.streamFormat),
            startOnLastChannel = valueOr(K.startLast, d.startOnLastChannel),
            zapWithUpDown = valueOr(K.zapUpDown, d.zapWithUpDown),
            lastChannelId = valueOr(K.lastChannel, d.lastChannelId),
            accent = valueOr(K.accent, d.accent),
            largeText = valueOr(K.largeText, d.largeText),
            warnVpn = valueOr(K.warnVpn, d.warnVpn),
            warnProxy = valueOr(K.warnProxy, d.warnProxy),
            downloads = valueOr(K.downloads, d.downloads),
            launcherRows = valueOr(K.launcherRows, d.launcherRows),
            showExternalIp = valueOr(K.showIp, d.showExternalIp),
            hideRestrictedRecent = valueOr(K.hideRecent, d.hideRestrictedRecent),
            hideRestrictedCarryOn = valueOr(K.hideCarryOn, d.hideRestrictedCarryOn),
            hideRestrictedSearch = valueOr(K.hideSearch, d.hideRestrictedSearch),
            pinHash = valueOr(K.pinHash, d.pinHash),
        )
    }

    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { prefs ->
            val s = transform(prefs.toSettings())
            // Where Live TV opened, until 1.2.1 (it now opens on Favourites each time).
            prefs.remove(stringPreferencesKey("last_live_filter"))
            prefs[K.activePlaylist] = s.activePlaylistId
            prefs[K.skipBack] = s.skipBackSec
            prefs[K.skipForward] = s.skipForwardSec
            prefs[K.passthrough] = s.audioPassthrough
            prefs[K.surroundAsDolby] = s.surroundAsDolby
            prefs[K.tmdbKey] = s.tmdbKey
            // Test settings of 1.22.1 to 1.22.3, since removed: their stored values go at the next save.
            prefs.remove(K.aacInApp)
            prefs.remove(K.aacUnchanged)
            prefs[K.subtitleMode] = s.subtitleMode.name
            prefs[K.subtitleLang] = s.subtitleLanguage
            prefs[K.buffer] = s.bufferSize.name
            prefs[K.format] = s.streamFormat.name
            prefs[K.startLast] = s.startOnLastChannel
            prefs[K.zapUpDown] = s.zapWithUpDown
            prefs[K.lastChannel] = s.lastChannelId
            prefs[K.accent] = s.accent
            prefs[K.largeText] = s.largeText
            prefs[K.warnVpn] = s.warnVpn
            prefs[K.warnProxy] = s.warnProxy
            prefs[K.downloads] = s.downloads
            prefs[K.launcherRows] = s.launcherRows
            prefs[K.showIp] = s.showExternalIp
            prefs[K.hideRecent] = s.hideRestrictedRecent
            prefs[K.hideCarryOn] = s.hideRestrictedCarryOn
            prefs[K.hideSearch] = s.hideRestrictedSearch
            prefs[K.pinHash] = s.pinHash
        }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E = enumOrNull<E>(name) ?: default

    private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? = name?.let { n -> enumValues<E>().firstOrNull { it.name == n } }

    private companion object {
        const val FORMAT_FOR = "stream_format_for_"
    }
}
