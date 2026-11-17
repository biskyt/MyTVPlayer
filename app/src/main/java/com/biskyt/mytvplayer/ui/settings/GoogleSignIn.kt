package com.biskyt.mytvplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.core.sync.DeviceCode
import com.biskyt.mytvplayer.core.sync.SyncException
import com.biskyt.mytvplayer.data.ProgressSync
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.QrCode
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A sign-in with Google by a code (one sign-in for the sync between TVs and for backups in Google Drive), for a
 * ViewModel's [scope].
 */
class GoogleSignIn(private val sync: ProgressSync, private val scope: CoroutineScope) {
    /** Where the sign-in got to. */
    sealed interface State {
        data object Idle : State

        data class Code(val code: DeviceCode) : State

        /** [code]: Google's own reason, when Google refused. */
        data class Failed(val reason: SyncException.Reason, val code: String? = null) : State
    }

    var state by mutableStateOf<State>(State.Idle)
        private set

    /** Counts the times the code panel closed: the page's buttons are back, and one must take the remote's focus. */
    var closed by mutableIntStateOf(0)
        private set

    private var job: Job? = null

    /** [turnOnSync]: see [ProgressSync.finishSignIn]. [then] runs once the user is signed in. */
    fun start(turnOnSync: Boolean, then: () -> Unit = {}) {
        job?.cancel()
        job = scope.launch {
            try {
                val code = sync.startSignIn()
                state = State.Code(code)
                sync.finishSignIn(code, turnOnSync)
                set(State.Idle)
                then()
            } catch (e: SyncException) {
                set(State.Failed(e.reason, e.code))
            }
        }
    }

    fun cancel() {
        job?.cancel()
        set(State.Idle)
    }

    private fun set(new: State) {
        if (state is State.Code) closed++
        state = new
    }
}

/** The code to type at Google's address, and a QR code that opens the address with the code filled in. */
@Composable
fun SignInCode(code: DeviceCode, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.sync_go_to, code.verificationUrl.removePrefix("https://")))
        Text(code.userCode, style = MaterialTheme.typography.displaySmall)
        QrCode(code.urlWithCode, Modifier.size(200.dp))
        Muted(stringResource(R.string.sync_waiting))
        PillButton(stringResource(R.string.sync_cancel), onCancel, rememberFirstFocus(code))
    }
}

/** Why the sign-in failed, for the user. */
@Composable
fun signInProblem(failed: GoogleSignIn.State.Failed): String = when {
    failed.reason == SyncException.Reason.DENIED -> stringResource(R.string.sync_denied)
    failed.reason == SyncException.Reason.EXPIRED -> stringResource(R.string.sync_expired)
    failed.code != null -> stringResource(R.string.sync_google_error, failed.code)
    else -> stringResource(R.string.sync_failed)
}
