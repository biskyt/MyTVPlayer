package com.biskyt.mytvplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.core.net.PhoneForm
import com.biskyt.mytvplayer.core.net.PhoneFormSeen
import com.biskyt.mytvplayer.core.net.PhoneFormText
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.QrCode
import com.biskyt.mytvplayer.ui.common.SettingRow
import com.biskyt.mytvplayer.ui.common.SidePanel
import com.biskyt.mytvplayer.ui.common.TvTextField
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the last Save found, shown under the key. */
enum class TmdbCheck { CHECKING, SAVED, REFUSED, UNREACHABLE }

class TmdbSettingsViewModel(private val c: AppContainer) : ViewModel() {
    var check by mutableStateOf<TmdbCheck?>(null)
        private set

    /**
     * Checks [key] with TMDB, then saves it: a key TMDB refused is not saved; one that could not be checked (no
     * network) is saved, as it may be right.
     */
    fun save(typed: String) = viewModelScope.launch {
        // A key or token is printable ASCII: a space, line end or other character pasted with it (from a phone, a web
        // page) is left out, so that it can't make a wrong request (or a header that HTTP refuses).
        val key = typed.filter { it in '!'..'~' }
        check = TmdbCheck.CHECKING
        val works = if (key.isEmpty()) false else c.tmdb.keyWorks(key)
        if (works != false) c.settings.update { it.copy(tmdbKey = key) }
        check = when (works) {
            true -> TmdbCheck.SAVED
            false -> TmdbCheck.REFUSED
            null -> TmdbCheck.UNREACHABLE
        }
    }

    /** The panel opened again: the last check's answer is no longer about what it shows. */
    fun forgetCheck() {
        if (check != TmdbCheck.CHECKING) check = null
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
internal fun TmdbKeyPanel(saved: String, onDismiss: () -> Unit) {
    val vm = appViewModel { TmdbSettingsViewModel(it) }
    var key by remember { mutableStateOf(saved) }
    LaunchedEffect(Unit) { vm.forgetCheck() }
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
            PhoneKeyEntry { sent ->
                key = sent
                save()
            }
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

/**
 * A QR code of a page that the TV serves on the home network while the panel is open ([PhoneForm]): the user opens it
 * on their phone and pastes their key there, instead of typing it with the remote. The key comes to [received] here.
 * Left out when the TV has no address on a home network.
 */
@Composable
private fun PhoneKeyEntry(received: (String) -> Unit) {
    val text = PhoneFormText(
        stringResource(R.string.settings_tmdb_phone_title),
        stringResource(R.string.settings_tmdb_phone_hint),
        stringResource(R.string.settings_tmdb_phone_send),
        stringResource(R.string.settings_tmdb_phone_done)
    )
    val onKey by rememberUpdatedState(received)
    var url by remember { mutableStateOf<String?>(null) }
    var seen by remember { mutableStateOf(PhoneFormSeen()) }
    // The key arrives on the thread of the phone's connection: it is handed to the panel on the main thread.
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        // Kept outside the IO block, so that it is closed even when the panel goes while the server starts.
        var form: PhoneForm? = null
        try {
            withContext(Dispatchers.IO) {
                form = PhoneForm.homeAddress()?.let { address ->
                    runCatching { PhoneForm(address, text) { key -> scope.launch { onKey(key) } } }.getOrNull()
                }
            }
            val f = form ?: return@LaunchedEffect
            url = f.url
            f.seen.collect { seen = it }
        } finally {
            form?.close()
        }
    }
    url?.let {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            QrCode(it, Modifier.size(150.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Muted(stringResource(R.string.settings_tmdb_phone), maxLines = 6)
                // Android lets a browser reach the home network only with this permission (Edge and Brave lacked it).
                Muted(stringResource(R.string.settings_tmdb_phone_nearby), maxLines = 4)
                // When a phone's browser shows no page: whether the phone reached the TV, and how.
                if (seen.connections > 0) {
                    Muted(
                        stringResource(R.string.settings_tmdb_phone_seen, seen.connections, seen.pages, seen.secure, seen.unanswered),
                        maxLines = 3
                    )
                }
            }
        }
    }
}
