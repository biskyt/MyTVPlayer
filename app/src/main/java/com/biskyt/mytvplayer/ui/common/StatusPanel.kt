package com.biskyt.mytvplayer.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.container
import com.biskyt.mytvplayer.data.SyncState
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What a settings page is doing, or did: progress while [working], then what happened. */
data class WorkStatus(val title: String, val detail: String? = null, val working: Boolean = false, val failed: Boolean = false)

/**
 * Runs a settings page's own operations one at a time, with [status] showing each one's progress and then its outcome.
 * They run in [appScope], so leaving the page doesn't stop them.
 */
class WorkRunner(private val appScope: CoroutineScope, private val scope: CoroutineScope, sync: StateFlow<SyncState>) {
    var status by mutableStateOf<WorkStatus?>(null)
        private set

    init {
        // A refresh started elsewhere (the daily update, a playlist) replaces an earlier outcome, so its own progress
        // and result show instead.
        scope.launch { sync.collect { if (it !is SyncState.Idle && it !is SyncState.Failed && status?.working != true) status = null } }
    }

    /** Shows [title] as working until [work] is done, then its outcome; false, doing nothing, while one is running. */
    @Suppress("TooGenericExceptionCaught") // e.g. full storage: shown, never allowed to crash the app
    fun run(title: String, work: suspend () -> WorkStatus): Boolean {
        if (status?.working == true) return false
        status = WorkStatus(title, working = true)
        scope.launch {
            status = try {
                appScope.async { work() }.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(appString(R.string.common_something_went_wrong), e)
            }
        }
        return true
    }

    /** Shows [outcome] (or nothing), unless an operation is running. */
    fun show(outcome: WorkStatus?) {
        if (status?.working != true) status = outcome
    }

    companion object {
        fun failed(title: String, e: Throwable) = WorkStatus(title, e.message, failed = true)
    }
}

/** Progress (the running step, e.g. "Loading guide: 40%") or the outcome, in one clearly visible box. */
@Composable
fun StatusPanel(status: WorkStatus?) {
    // Read here, not by the page: progress ticks then redraw just this box.
    val sync = LocalContext.current.container.sync.state.collectAsState().value
    // Not an earlier failure: that isn't what is happening now.
    // The update shows in the corner too (SyncToast): here, under the page's own work.
    val progress =
        sync.takeUnless { it is SyncState.Failed }?.let(::syncText)?.let { listOfNotNull(it.title, it.detail).joinToString("\n") }
            ?: stringResource(R.string.common_please_wait)
    val shown = when {
        status?.working == true -> status.copy(detail = progress)
        status != null -> status
        sync is SyncState.Failed -> WorkStatus(stringResource(R.string.common_last_update_failed), sync.message, failed = true)
        else -> return
    }
    val colour = if (shown.failed) Palette.warning else LocalAccent.current.color
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(12.dp)).background(Palette.surfaceLow)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(6.dp).fillMaxHeight().background(colour))
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (shown.working) "${shown.title}…" else shown.title, style = MaterialTheme.typography.titleMedium, color = colour)
            shown.detail?.let { Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
    }
}
