package com.biskyt.mytvplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.SettingRow
import com.biskyt.mytvplayer.ui.common.SidePanel
import com.biskyt.mytvplayer.ui.common.TvTextField
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import kotlinx.coroutines.launch

/** What the last Save found, shown under the key. */
enum class TmdbCheck { CHECKING, SAVED, REFUSED, UNREACHABLE }

class TmdbSettingsViewModel(private val c: AppContainer) : ViewModel() {
    var check by mutableStateOf<TmdbCheck?>(null)
        private set

    /**
     * Checks [key] with TMDB, then saves it: a key TMDB refused is not saved; one that could not be checked (no
     * network) is saved, as it may be right.
     */
    fun save(key: String) = viewModelScope.launch {
        check = TmdbCheck.CHECKING
        val works = c.tmdb.keyWorks(key)
        if (works != false) c.settings.update { it.copy(tmdbKey = key.trim()) }
        check = when (works) {
            true -> TmdbCheck.SAVED
            false -> TmdbCheck.REFUSED
            null -> TmdbCheck.UNREACHABLE
        }
    }

    fun remove() = viewModelScope.launch {
        c.settings.update { it.copy(tmdbKey = "") }
        check = null
    }
}

/**
 * "TMDB": off until the user adds their own TMDB key (the owner, 2026-11-16). With a key, film and series pages show
 * the cast and crew, trailers, links and more from TMDB. Without one, the app makes no requests to TMDB.
 */
@Composable
fun TmdbRow() {
    val vm = appViewModel { SettingsViewModel(it) }
    val s = vm.settings.collectAsState().value ?: return
    var open by remember { mutableStateOf(false) }
    val on = s.tmdbKey.isNotBlank()
    SettingRow(
        stringResource(R.string.settings_tmdb),
        stringResource(if (on) R.string.settings_tmdb_on else R.string.settings_tmdb_help),
        { open = true }
    )
    if (open) TmdbKeyPanel(s.tmdbKey) { open = false }
}

@Composable
private fun TmdbKeyPanel(saved: String, onDismiss: () -> Unit) {
    val vm = appViewModel { TmdbSettingsViewModel(it) }
    var key by remember { mutableStateOf(saved) }
    val save = { if (key.isNotBlank()) vm.save(key) }
    SidePanel(onDismiss, width = 520.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.settings_tmdb), style = MaterialTheme.typography.titleLarge)
            Muted(stringResource(R.string.settings_tmdb_explain), maxLines = 6)
            TvTextField(
                key,
                { key = it },
                stringResource(R.string.settings_tmdb_key_hint),
                rememberFirstFocus().fillMaxWidth(),
                // A key is letters and digits: no capitals or corrections from the keyboard.
                keyboardType = KeyboardType.Ascii,
                autoCorrect = false,
                onDone = save
            )
            vm.check?.let { Muted(stringResource(checkText(it)), maxLines = 3) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(stringResource(R.string.settings_tmdb_save), save, primary = true)
                if (saved.isNotBlank()) {
                    // The panel closes: this button goes once the key is removed, and focus must not be lost with it.
                    PillButton(stringResource(R.string.settings_tmdb_remove), {
                        vm.remove()
                        onDismiss()
                    })
                }
            }
        }
    }
}

private fun checkText(check: TmdbCheck) = when (check) {
    TmdbCheck.CHECKING -> R.string.settings_tmdb_checking
    TmdbCheck.SAVED -> R.string.settings_tmdb_saved
    TmdbCheck.REFUSED -> R.string.settings_tmdb_refused
    TmdbCheck.UNREACHABLE -> R.string.settings_tmdb_unreachable
}
