package com.biskyt.mytvplayer.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.container
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PinGateHost
import com.biskyt.mytvplayer.ui.common.PinPanel
import com.biskyt.mytvplayer.ui.common.SettingRow
import com.biskyt.mytvplayer.ui.common.Toggle
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.common.rememberPinGate
import kotlinx.coroutines.launch

/** The "Parental controls" row in Settings: with a PIN set, it is asked for first. */
@Composable
fun ParentalControlsRow() {
    val nav = LocalNavigator.current
    val parental = LocalContext.current.container.parental
    val gate = rememberPinGate()
    SettingRow(stringResource(R.string.parental_title), stringResource(R.string.parental_help), {
        gate.run(parental.hasPin.value) { nav.push(Screen.Parental) }
    })
    PinGateHost(gate)
}

/** Settings › Parental controls: what restricted categories are hidden from, and the PIN. */
@Composable
fun ParentalScreen() {
    val vm = appViewModel { SettingsViewModel(it) }
    val s = vm.settings.collectAsState().value ?: return
    val parental = LocalContext.current.container.parental
    val scope = rememberCoroutineScope()
    var settingPin by remember { mutableStateOf(false) }
    val hasPin = s.pinHash.isNotEmpty()
    SettingsPage(stringResource(R.string.parental_title)) {
        item { Muted(stringResource(R.string.parental_intro), maxLines = 3) }
        item {
            SettingRow(stringResource(R.string.parental_hide_recent), null, {
                vm.update { it.copy(hideRestrictedRecent = !it.hideRestrictedRecent) }
            }, rememberFirstFocus()) { Toggle(s.hideRestrictedRecent) }
        }
        item {
            SettingRow(stringResource(R.string.parental_hide_carry_on), null, {
                vm.update { it.copy(hideRestrictedCarryOn = !it.hideRestrictedCarryOn) }
            }) { Toggle(s.hideRestrictedCarryOn) }
        }
        item {
            SettingRow(stringResource(R.string.parental_hide_search), null, {
                vm.update { it.copy(hideRestrictedSearch = !it.hideRestrictedSearch) }
            }) { Toggle(s.hideRestrictedSearch) }
        }
        item {
            val help = stringResource(if (hasPin) R.string.parental_pin_set_help else R.string.parental_pin_none)
            SettingRow(stringResource(if (hasPin) R.string.parental_change_pin else R.string.parental_set_pin), help, {
                settingPin = true
            })
        }
        if (hasPin) {
            item {
                SettingRow(stringResource(R.string.parental_remove_pin), null, { scope.launch { parental.setPin("") } })
            }
        }
    }
    if (settingPin) NewPinPanels({ settingPin = false }) { pin -> scope.launch { parental.setPin(pin) } }
}

/** Choosing a new PIN: typed twice, the same both times, then [onChosen]. A different second PIN starts again. */
@Composable
private fun NewPinPanels(onDismiss: () -> Unit, onChosen: (String) -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    val title = stringResource(if (first == null) R.string.parental_pin_new else R.string.parental_pin_confirm)
    PinPanel(title, onDismiss, wrong = stringResource(R.string.parental_pin_mismatch)) { pin ->
        val chosen = first
        when {
            chosen == null -> true.also { first = pin }
            pin == chosen -> true.also {
                onChosen(pin)
                onDismiss()
            }
            else -> false.also { first = null }
        }
    }
}
