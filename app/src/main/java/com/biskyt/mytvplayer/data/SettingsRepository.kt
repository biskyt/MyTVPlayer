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
    val warnVpn: Boolean = false
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

    private fun Preferences.toSettings(): Settings {
        val d = Settings()
        return Settings(
            activePlaylistId = this[K.activePlaylist] ?: d.activePlaylistId,
            skipBackSec = this[K.skipBack] ?: d.skipBackSec,
            skipForwardSec = this[K.skipForward] ?: d.skipForwardSec,
            audioPassthrough = this[K.passthrough] ?: d.audioPassthrough,
            subtitleMode = enumOr(this[K.subtitleMode], d.subtitleMode),
            subtitleLanguage = this[K.subtitleLang] ?: d.subtitleLanguage,
            bufferSize = enumOr(this[K.buffer], d.bufferSize),
            streamFormat = enumOr(this[K.format], d.streamFormat),
            startOnLastChannel = this[K.startLast] ?: d.startOnLastChannel,
            zapWithUpDown = this[K.zapUpDown] ?: d.zapWithUpDown,
            lastChannelId = this[K.lastChannel] ?: d.lastChannelId,
            accent = this[K.accent] ?: d.accent,
            largeText = this[K.largeText] ?: d.largeText,
            warnVpn = this[K.warnVpn] ?: d.warnVpn,
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
        }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E = enumOrNull<E>(name) ?: default

    private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? = name?.let { n -> enumValues<E>().firstOrNull { it.name == n } }

    private companion object {
        const val FORMAT_FOR = "stream_format_for_"
    }
}
