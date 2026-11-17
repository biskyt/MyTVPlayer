package com.biskyt.mytvplayer.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.container
import com.biskyt.mytvplayer.core.sync.SyncException
import com.biskyt.mytvplayer.data.ProgressSync
import com.biskyt.mytvplayer.data.SyncAccount
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.SettingRow
import com.biskyt.mytvplayer.ui.common.formatDateTime
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SyncViewModel(private val sync: ProgressSync) : ViewModel() {
    val google = GoogleSignIn(sync, viewModelScope)

    /** The last sync did not finish. */
    val failure = sync.failure

    val account = sync.account.state.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun start() = google.start(turnOnSync = true)

    /** With the sign-in kept from a backup in Drive, or from before the sync stopped. */
    fun turnOn() {
        viewModelScope.launch { sync.turnOn() }
    }

    /** In the background: "Last synced" changes when it is done. */
    fun syncNow() = sync.syncSoon(force = true)

    fun stop() = sync.stop()

    fun signOut() = sync.signOut()
}

/** The "Sync between TVs" row in Settings, when the app was built with a Google client. */
@Composable
fun SyncRow() {
    if (!LocalContext.current.container.progressSync.available) return
    val nav = LocalNavigator.current
    SettingRow(stringResource(R.string.sync_title), stringResource(R.string.sync_help), { nav.push(Screen.Sync) })
}

/**
 * Settings › Sync between TVs: sign in with Google (a code to type on a phone, or a QR code), turn the sync on or off,
 * or sign out.
 */
@Composable
fun SyncScreen(vm: SyncViewModel = appViewModel { SyncViewModel(it.progressSync) }) {
    val account = vm.account.collectAsState().value ?: return
    val failure by vm.failure.collectAsState()
    SettingsPage(stringResource(R.string.sync_title)) {
        item { Muted(stringResource(R.string.sync_intro), maxLines = 5) }
        when {
            account.on -> signedIn(vm, account, failure)
            account.signedIn -> syncOff(vm, account)
            else -> signIn(vm)
        }
    }
}

private fun LazyListScope.signedIn(vm: SyncViewModel, account: SyncAccount.State, failure: SyncException?) {
    item {
        val last = when {
            failure != null -> failure.code?.let { stringResource(R.string.sync_failed_code, it) } ?: stringResource(R.string.sync_failed)
            account.lastSyncedAt > 0 -> stringResource(R.string.sync_last, formatDateTime(account.lastSyncedAt))
            else -> stringResource(R.string.sync_never)
        }
        Column {
            Text(who(account), style = MaterialTheme.typography.titleSmall)
            Muted(last)
        }
    }
    item { SettingRow(stringResource(R.string.sync_now), null, { vm.syncNow() }, rememberFirstFocus(once = true)) }
    item { SettingRow(stringResource(R.string.sync_stop), stringResource(R.string.sync_stop_help), { vm.stop() }) }
    signOut(vm)
}

/** Signed in (for a backup in Drive, or kept when the sync stopped), with the sync off. */
private fun LazyListScope.syncOff(vm: SyncViewModel, account: SyncAccount.State) {
    item { Text(who(account), style = MaterialTheme.typography.titleSmall) }
    item { SettingRow(stringResource(R.string.sync_turn_on), null, { vm.turnOn() }, rememberFirstFocus(once = true)) }
    signOut(vm)
}

private fun LazyListScope.signOut(vm: SyncViewModel) =
    item { SettingRow(stringResource(R.string.sync_sign_out), stringResource(R.string.sync_sign_out_help), { vm.signOut() }) }

@Composable
private fun who(account: SyncAccount.State) =
    account.email?.let { stringResource(R.string.sync_signed_in_as, it) } ?: stringResource(R.string.sync_signed_in)

private fun LazyListScope.signIn(vm: SyncViewModel) {
    when (val s = vm.google.state) {
        is GoogleSignIn.State.Code -> item { SignInCode(s.code, vm.google::cancel) }
        else -> item {
            val problem = (s as? GoogleSignIn.State.Failed)?.let { signInProblem(it) }
            SettingRow(stringResource(R.string.sync_sign_in), problem, { vm.start() }, rememberFirstFocus())
        }
    }
}
