package com.biskyt.mytvplayer.ui.common

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.ui.theme.bd

/**
 * A web page as a QR code to scan with a phone (paying or reading on a TV isn't for everyone), with its address, an
 * optional [note], and a way to open it here.
 */
@Composable
fun LinkPanel(title: String, url: String, note: String? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var problem by remember { mutableStateOf<String?>(null) }
    BoardPanel(onDismiss, gap = 10.bd) {
        BoardPanelTitle(title)
        BoardPanelSubtitle(stringResource(R.string.common_link_scan_hint))
        QrCode(url, Modifier.size(240.dp).align(Alignment.CenterHorizontally))
        BoardPanelText(url.removePrefix("https://"))
        note?.let { BoardPanelSubtitle(it) }
        PillButton(stringResource(R.string.common_link_open_here), {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                problem = appString(R.string.common_link_no_browser)
            } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
                // Some TVs refuse in other ways (e.g. a browser that's switched off): never a crash.
                problem = appString(R.string.common_link_open_failed, e.javaClass.simpleName)
            }
        }, rememberFirstFocus())
        problem?.let { BoardPanelSubtitle(it) }
    }
}
