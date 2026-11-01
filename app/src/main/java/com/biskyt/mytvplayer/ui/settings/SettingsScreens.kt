package com.biskyt.mytvplayer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.AppText
import com.biskyt.mytvplayer.BuildConfig
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.container
import com.biskyt.mytvplayer.core.player.SubtitleMode
import com.biskyt.mytvplayer.core.ui.AccentPalette
import com.biskyt.mytvplayer.data.BufferSize
import com.biskyt.mytvplayer.data.Settings
import com.biskyt.mytvplayer.data.StreamFormat
import com.biskyt.mytvplayer.data.accentName
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.CycleSettingRow
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.SettingRow
import com.biskyt.mytvplayer.ui.common.Toggle
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.cycle
import com.biskyt.mytvplayer.ui.common.displayLanguage
import com.biskyt.mytvplayer.ui.common.nativeLanguageName
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import java.util.Locale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun update(transform: (Settings) -> Settings) = viewModelScope.launch { c.settings.update(transform) }

    fun setStreamFormat(format: StreamFormat) = viewModelScope.launch { c.settings.setStreamFormat(format) }
}

/** Title + scrollable column used by every settings page. */
@Composable
fun SettingsPage(title: String, content: LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 40.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(bottom = 20.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 40.dp), content = content)
    }
}

@Composable
fun SettingsScreen() {
    val nav = LocalNavigator.current
    val pid = LocalContext.current.container.settings.settings.collectAsState(initial = null).value?.activePlaylistId ?: 0
    SettingsPage(stringResource(R.string.common_settings)) {
        item {
            SettingRow(
                stringResource(R.string.settings_playlists_title),
                stringResource(R.string.settings_playlists_help),
                { nav.push(Screen.Playlists) },
                rememberFirstFocus()
            )
        }
        item { LanguageRow() }
        item {
            SettingRow(stringResource(R.string.settings_playback_title), stringResource(R.string.settings_playback_help), {
                nav.push(Screen.PlaybackSettings)
            })
        }
        item {
            SettingRow(stringResource(R.string.settings_categories_title), stringResource(R.string.settings_categories_help), {
                nav.push(Screen.Categories(pid))
            })
        }
        item {
            SettingRow(stringResource(R.string.common_guide_sources), stringResource(R.string.settings_guide_sources_help), {
                nav.push(Screen.GuideSources)
            })
        }
        item {
            SettingRow(stringResource(R.string.common_guide_matching), stringResource(R.string.settings_guide_matching_help), {
                nav.push(Screen.GuideMatching(pid))
            })
        }
        item {
            SettingRow(stringResource(R.string.settings_appearance_title), stringResource(R.string.settings_appearance_help), {
                nav.push(Screen.Appearance)
            })
        }
        item { VpnWarningRow() }
        item {
            SettingRow(stringResource(R.string.settings_backup_title), stringResource(R.string.settings_backup_help), {
                nav.push(Screen.Backup)
            })
        }
        item {
            val help = stringResource(R.string.settings_about_help, BuildConfig.VERSION_NAME)
            SettingRow(stringResource(R.string.settings_about_title), help, { nav.push(Screen.About) })
        }
        item { SupportSection() }
    }
}

/** "Warn if VPN is not connected" (see VpnMonitor): shown in the top bar. */
@Composable
private fun VpnWarningRow() {
    val vm = appViewModel { SettingsViewModel(it) }
    val on = vm.settings.collectAsState().value?.warnVpn ?: return
    SettingRow(stringResource(R.string.settings_warn_vpn), stringResource(R.string.settings_warn_vpn_help), {
        vm.update { it.copy(warnVpn = !it.warnVpn) }
    }) { Toggle(on) }
}

@Composable
fun PlaybackSettingsScreen() {
    val vm = appViewModel { SettingsViewModel(it) }
    val s = vm.settings.collectAsState().value ?: return
    val skipSteps = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120)
    SettingsPage(stringResource(R.string.settings_playback_title)) {
        item {
            CycleSettingRow(
                stringResource(R.string.common_skip_back),
                stringResource(R.string.settings_skip_back_help),
                stringResource(R.string.settings_seconds, s.skipBackSec),
                { d -> vm.update { it.copy(skipBackSec = skipSteps.cycle(it.skipBackSec, d)) } },
                rememberFirstFocus()
            )
        }
        item {
            CycleSettingRow(
                stringResource(R.string.common_skip_forward),
                stringResource(R.string.settings_skip_forward_help),
                stringResource(R.string.settings_seconds, s.skipForwardSec),
                { d -> vm.update { it.copy(skipForwardSec = skipSteps.cycle(it.skipForwardSec, d)) } }
            )
        }
        item {
            SettingRow(
                stringResource(R.string.settings_passthrough),
                stringResource(R.string.settings_passthrough_help),
                { vm.update { it.copy(audioPassthrough = !it.audioPassthrough) } }
            ) { Toggle(s.audioPassthrough) }
        }
        item {
            val mode = when (s.subtitleMode) {
                SubtitleMode.OFF -> R.string.common_subtitles_off
                SubtitleMode.FORCED_ONLY -> R.string.settings_subtitles_forced_only
                SubtitleMode.ALWAYS -> R.string.settings_subtitles_always
            }
            CycleSettingRow(
                stringResource(R.string.settings_subtitles),
                stringResource(R.string.settings_subtitles_help),
                stringResource(mode),
                { d -> vm.update { it.copy(subtitleMode = SubtitleMode.entries.cycle(it.subtitleMode, d)) } }
            )
        }
        item {
            val languages = listOf("", "en", "fr", "de", "es", "it", "pt", "nl", "pl", "tr", "ar")
            val label = if (s.subtitleLanguage.isEmpty()) {
                stringResource(R.string.settings_language_system, displayLanguage(Locale.getDefault().language))
            } else {
                displayLanguage(s.subtitleLanguage)
            }
            CycleSettingRow(stringResource(R.string.settings_subtitle_language), null, label, { d ->
                vm.update { it.copy(subtitleLanguage = languages.cycle(it.subtitleLanguage, d)) }
            })
        }
        item {
            val label = when (s.bufferSize) {
                BufferSize.SMALL -> R.string.settings_buffer_small
                BufferSize.NORMAL -> R.string.settings_buffer_normal
                BufferSize.LARGE -> R.string.settings_buffer_large
            }
            CycleSettingRow(
                stringResource(R.string.settings_buffer),
                stringResource(R.string.settings_buffer_help),
                stringResource(label),
                { d -> vm.update { it.copy(bufferSize = BufferSize.entries.cycle(it.bufferSize, d)) } }
            )
        }
        item {
            CycleSettingRow(
                stringResource(R.string.settings_stream_format),
                stringResource(R.string.settings_stream_format_help),
                if (s.streamFormat == StreamFormat.TS) "MPEG-TS" else "HLS",
                { d -> vm.setStreamFormat(StreamFormat.entries.cycle(s.streamFormat, d)) }
            )
        }
        item {
            SettingRow(stringResource(R.string.settings_start_last), stringResource(R.string.settings_start_last_help), {
                vm.update { it.copy(startOnLastChannel = !it.startOnLastChannel) }
            }) {
                Toggle(s.startOnLastChannel)
            }
        }
        item {
            SettingRow(stringResource(R.string.settings_zap_up_down), stringResource(R.string.settings_zap_up_down_help), {
                vm.update { it.copy(zapWithUpDown = !it.zapWithUpDown) }
            }) {
                Toggle(s.zapWithUpDown)
            }
        }
    }
}

/** The app's language: each press moves to the next, and the screens switch to it straight away. */
@Composable
private fun LanguageRow() {
    val context = LocalContext.current
    val chosen by AppText.chosen.collectAsState()
    val label = if (chosen.isEmpty()) {
        stringResource(R.string.settings_language_system, nativeLanguageName(AppText.locale.toLanguageTag(), withCountry = false))
    } else {
        nativeLanguageName(chosen)
    }
    CycleSettingRow(stringResource(R.string.settings_language), null, label, { d ->
        AppText.setLanguage(context, (listOf("") + AppText.languages).cycle(chosen, d))
    })
}

@Composable
fun AppearanceScreen() {
    val vm = appViewModel { SettingsViewModel(it) }
    val s = vm.settings.collectAsState().value ?: return
    Column(
        Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(stringResource(R.string.settings_appearance_title), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.settings_accent), style = MaterialTheme.typography.titleLarge)
        Muted(stringResource(R.string.settings_accent_help), maxLines = 2)
        LazyVerticalGrid(
            GridCells.Fixed(4),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.height(140.dp)
        ) {
            items(AccentPalette.presets) { (id, argb) ->
                val selected = s.accent == argb
                TvCard(
                    onClick = { vm.update { it.copy(accent = argb) } },
                    selected = selected,
                    modifier = if (argb ==
                        AccentPalette.presets.first().argb
                    ) {
                        rememberFirstFocus()
                    } else {
                        Modifier
                    }
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(30.dp).clip(CircleShape).background(Color(argb)))
                        Text(
                            stringResource(accentName(id)),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 10.dp)
                        )
                    }
                }
            }
        }
        SettingRow(stringResource(R.string.settings_large_text), stringResource(R.string.settings_large_text_help), {
            vm.update { it.copy(largeText = !it.largeText) }
        }) { Toggle(s.largeText) }
    }
}

@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val storage = remember {
        fun size(f: java.io.File?): Long = f?.walkTopDown()?.sumOf { it.length() } ?: 0
        Triple(
            size(context.getDatabasePath("mytvplayer.db").parentFile),
            size(context.filesDir.resolve("epg")),
            size(context.cacheDir.resolve("logos"))
        )
    }
    fun mb(bytes: Long) = android.text.format.Formatter.formatShortFileSize(context, bytes)
    val nav = LocalNavigator.current
    SettingsPage(stringResource(R.string.settings_about_title)) {
        item { Text("MyTVPlayer ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge) }
        item { Muted(stringResource(R.string.settings_about_licence), maxLines = 2) }
        item {
            SettingRow(
                stringResource(R.string.settings_licences_title),
                stringResource(R.string.settings_licences_help),
                { nav.push(Screen.Licences) },
                rememberFirstFocus()
            )
        }
        item {
            Column(Modifier.width(700.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.settings_storage),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp)
                )
                Muted(stringResource(R.string.settings_storage_database, mb(storage.first)))
                Muted(stringResource(R.string.settings_storage_guides, mb(storage.second)))
                Muted(stringResource(R.string.settings_storage_logos, mb(storage.third)))
                Muted(stringResource(R.string.settings_storage_help), maxLines = 3)
            }
        }
        item { SupportSection() }
    }
}
