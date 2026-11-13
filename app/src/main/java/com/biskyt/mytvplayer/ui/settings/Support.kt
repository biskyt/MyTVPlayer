package com.biskyt.mytvplayer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.ui.common.AppIcons
import com.biskyt.mytvplayer.ui.common.LinkPanel
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.cardColor

/** Where to support the app: the name on its button, and the page's address (also in README.md and .github/FUNDING.yml). */
enum class SupportLink(val label: String, val url: String, val icon: ImageVector, val tint: Color) {
    KO_FI("Ko-fi", "https://ko-fi.com/biskyt", AppIcons.Cup, Color(0xFFFF5E5B)),
    GITHUB_SPONSORS("GitHub Sponsors", "https://github.com/sponsors/biskyt", AppIcons.Heart, Color(0xFFDB61A2))
}

/** At the foot of Settings and About: how to support the app. Each button shows a QR code to finish on a phone. */
@Composable
fun SupportSection() {
    var shown by remember { mutableStateOf<SupportLink?>(null) }
    Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.settings_support_title), style = MaterialTheme.typography.titleLarge)
        Muted(stringResource(R.string.settings_support_help), maxLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SupportLink.entries.forEach { link ->
                PillButton(link.label, {
                    shown = link
                }) { Icon(link.icon, null, tint = cardColor(link.tint), modifier = Modifier.size(20.dp)) }
            }
        }
    }
    shown?.let { link -> LinkPanel(link.label, link.url) { shown = null } }
}
