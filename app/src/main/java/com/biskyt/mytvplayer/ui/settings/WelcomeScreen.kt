package com.biskyt.mytvplayer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.theme.Palette
import kotlinx.coroutines.launch

/** First launch: the free channels, films and series, when the user picks them. */
class WelcomeViewModel(private val c: AppContainer) : ViewModel() {
    var error by mutableStateOf<String?>(null)
        private set
    var working by mutableStateOf(false)
        private set
    var addedId by mutableStateOf<Long?>(null)
        private set

    fun start() {
        if (working) return
        error = null
        working = true
        viewModelScope.launch {
            c.playlists.addFree().onSuccess { addedId = it }.onFailure { error = it.message }
            working = false
        }
    }
}

@Composable
fun WelcomeScreen() {
    val vm = appViewModel { WelcomeViewModel(it) }
    val nav = LocalNavigator.current
    vm.addedId?.let { id ->
        // Next: choose which countries' channels to load (the TV's own country is preselected).
        LaunchedEffect(id) { nav.replace(Screen.Categories(id, firstRun = true)) }
    }
    SettingsColumn(
        Modifier.background(Palette.background),
        spacing = 18.dp,
        tvPadding = PaddingValues(horizontal = 96.dp, vertical = 72.dp)
    ) {
        // The first screen: nothing to go back to.
        SettingsTitle(stringResource(R.string.settings_welcome_title), style = MaterialTheme.typography.displaySmall, onBack = null)
        Muted(
            stringResource(R.string.settings_welcome_intro),
            Modifier.widthIn(max = 820.dp),
            maxLines = touchLines(3, 5),
        )
        val error = vm.error
        when {
            vm.working -> Text(stringResource(R.string.settings_welcome_downloading), style = MaterialTheme.typography.titleMedium)
            error != null -> Text(error, style = MaterialTheme.typography.titleMedium, color = Palette.warning)
        }
        ButtonRow {
            PillButton(
                stringResource(R.string.settings_welcome_add_provider),
                { nav.replace(Screen.PlaylistEdit(null, firstRun = true)) },
                if (error == null) rememberFirstFocus() else Modifier,
                primary = true,
            )
            // Stays on screen while setting up, so the button pressed keeps focus.
            PillButton(
                stringResource(
                    when {
                        vm.working -> R.string.settings_welcome_setting_up
                        error != null -> R.string.settings_welcome_free_retry
                        else -> R.string.settings_welcome_free
                    }
                ),
                vm::start,
                if (error != null) rememberFirstFocus(error) else Modifier,
            )
            // After reinstalling: everything back from a backup file.
            PillButton(stringResource(R.string.settings_welcome_restore), { nav.push(Screen.Backup) })
        }
    }
}
