package com.biskyt.mytvplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.data.CrashLog
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.QrCode
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.openLink
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.theme.Palette
import com.biskyt.mytvplayer.ui.theme.showsQrCodes

/**
 * After a crash: what went wrong, a code to send it from a phone (on a phone or tablet, a button that opens it), and a
 * way in that doesn't update the playlist first
 * (in case that's what crashed). [onContinue] gets whether to update as usual.
 */
@Composable
fun CrashScreen(report: String, onContinue: (updating: Boolean) -> Unit) {
    val issue = remember(report) { CrashLog.issueUrl(report) }
    // A block of lines per card: a few presses of the remote get through even a long report.
    val blocks = remember(report) { report.lines().filter { it.isNotBlank() }.chunked(10).map { it.joinToString("\n") } }
    Row(
        Modifier.fillMaxSize().background(Palette.background).padding(horizontal = 72.dp, vertical = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.app_crash_title), style = MaterialTheme.typography.headlineMedium)
            val qr = showsQrCodes()
            Muted(stringResource(if (qr) R.string.app_crash_help else R.string.app_crash_help_touch), maxLines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(stringResource(R.string.app_crash_continue), { onContinue(true) }, rememberFirstFocus(), primary = true)
                PillButton(stringResource(R.string.app_crash_continue_no_update), { onContinue(false) })
                // On a phone or tablet, in place of the code: the report, opened here.
                if (!qr) {
                    val context = LocalContext.current
                    PillButton(stringResource(R.string.app_crash_report), { openLink(context, issue) })
                }
            }
            // The whole report, in focusable blocks so the remote can scroll through it.
            LazyColumn(Modifier.weight(1f).background(Palette.surfaceLow).padding(12.dp)) {
                items(blocks) { block ->
                    TvCard(onClick = {}, color = Palette.surfaceLow) {
                        Text(
                            block,
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.textMuted,
                            modifier = Modifier.padding(6.dp)
                        )
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Not on a phone or tablet, which would scan it with itself (the owner, 2026-11-25).
            if (showsQrCodes()) QrCode(issue, Modifier.size(320.dp))
            Muted(CrashLog.ISSUES.removePrefix("https://").substringBeforeLast('/'))
        }
    }
}
