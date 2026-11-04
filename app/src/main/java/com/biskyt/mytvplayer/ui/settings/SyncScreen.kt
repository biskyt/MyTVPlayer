package com.biskyt.mytvplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.container
import com.biskyt.mytvplayer.core.sync.DeviceCode
import com.biskyt.mytvplayer.core.sync.SyncException
import com.biskyt.mytvplayer.data.ProgressSync
import com.biskyt.mytvplayer.data.SyncAccount
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.QrCode
import com.biskyt.mytvplayer.ui.common.SettingRow
import com.biskyt.mytvplayer.ui.common.formatDateTime
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SyncViewModel(private val sync: ProgressSync) : ViewModel() {
    /** Where the sign-in got to. */
    sealed interface SignIn {
        data object Idle : SignIn

        data class Code(val code: DeviceCode) : SignIn

        /** [code]: Google's own reason, when Google refused. */
        data class Failed(val reason: SyncException.Reason, val code: String? = null) : SignIn
    }

    var signIn by mutableStateOf<SignIn>(SignIn.Idle)
        private set

    /** The last sync did not finish. */
    val failure = sync.failure

    val account = sync.account.state.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private var job: Job? = null

    fun start() {
        job?.cancel()
        job = viewModelScope.launch {
            try {
                val code = sync.startSignIn()
                signIn = SignIn.Code(code)
                sync.finishSignIn(code)
                signIn = SignIn.Idle
            } catch (e: SyncException) {
                signIn = SignIn.Failed(e.reason, e.code)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        signIn = SignIn.Idle
    }

    /** In the background: "Last synced" changes when it is done. */
    fun syncNow() = sync.syncSoon(force = true)

    fun stop() = sync.stop()
}

/** The "Sync between TVs" row in Settings, when the app was built with a Google client. */
@Composable
fun SyncRow() {
    if (!LocalContext.current.container.progressSync.available) return
    val nav = LocalNavigator.current
    SettingRow(stringResource(R.string.sync_title), stringResource(R.string.sync_help), { nav.push(Screen.Sync) })
}

/** Settings › Sync between TVs: sign in with Google (a code to type on a phone, or a QR code), or stop. */
@Composable
fun SyncScreen(vm: SyncViewModel = appViewModel { SyncViewModel(it.progressSync) }) {
    val account = vm.account.collectAsState().value ?: return
    val failure by vm.failure.collectAsState()
    SettingsPage(stringResource(R.string.sync_title)) {
        item { Muted(stringResource(R.string.sync_intro), maxLines = 4) }
        if (account.on) signedIn(vm, account, failure) else signIn(vm)
    }
}

private fun LazyListScope.signedIn(vm: SyncViewModel, account: SyncAccount.State, failure: SyncException?) {
    item {
        val who = account.email?.let { stringResource(R.string.sync_signed_in_as, it) } ?: stringResource(R.string.sync_signed_in)
        val last = when {
            failure != null -> failure.code?.let { stringResource(R.string.sync_failed_code, it) } ?: stringResource(R.string.sync_failed)
            account.lastSyncedAt > 0 -> stringResource(R.string.sync_last, formatDateTime(account.lastSyncedAt))
            else -> stringResource(R.string.sync_never)
        }
        Column {
            Text(who, style = MaterialTheme.typography.titleSmall)
            Muted(last)
        }
    }
    item { SettingRow(stringResource(R.string.sync_now), null, { vm.syncNow() }, rememberFirstFocus()) }
    item { SettingRow(stringResource(R.string.sync_stop), stringResource(R.string.sync_stop_help), { vm.stop() }) }
}

private fun LazyListScope.signIn(vm: SyncViewModel) {
    when (val s = vm.signIn) {
        is SyncViewModel.SignIn.Code -> item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.sync_go_to, s.code.verificationUrl.removePrefix("https://")))
                Text(s.code.userCode, style = MaterialTheme.typography.displaySmall)
                QrCode(s.code.verificationUrl, Modifier.size(200.dp))
                Muted(stringResource(R.string.sync_waiting))
                PillButton(stringResource(R.string.sync_cancel), { vm.cancel() }, rememberFirstFocus(s))
            }
        }
        else -> item {
            val problem = (s as? SyncViewModel.SignIn.Failed)?.let {
                when {
                    it.reason == SyncException.Reason.DENIED -> stringResource(R.string.sync_denied)
                    it.reason == SyncException.Reason.EXPIRED -> stringResource(R.string.sync_expired)
                    it.code != null -> stringResource(R.string.sync_google_error, it.code)
                    else -> stringResource(R.string.sync_failed)
                }
            }
            SettingRow(stringResource(R.string.sync_sign_in), problem, { vm.start() }, rememberFirstFocus())
        }
    }
}
