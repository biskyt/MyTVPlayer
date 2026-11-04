package com.biskyt.mytvplayer.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.ui.common.LinkPanel
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.SettingRow
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus

/** The licences the app's open-source parts come under. */
enum class Licence(val title: String, val url: String) {
    APACHE("Apache 2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
    GPL3("GPL 3.0", "https://www.gnu.org/licenses/gpl-3.0.html"),
    OFL("SIL Open Font License 1.1", "https://openfontlicense.org")
}

/**
 * Open-source software in the app: [modules] are the Maven modules it covers (a test keeps them, and [version], in
 * step with the build's dependencies).
 */
data class OpenSourceNotice(
    val name: String,
    val version: String?,
    /** What the app uses it for. */
    @param:StringRes val use: Int,
    val licence: Licence,
    val url: String,
    val modules: List<String> = emptyList()
) {
    val title get() = listOfNotNull(name, version).joinToString(" ")
}

/** What the app is built from (its bill of materials), as listed on the licences page. */
val openSourceNotices = listOf(
    OpenSourceNotice(
        "AndroidX Media3",
        "1.8.0",
        R.string.settings_lib_media3,
        Licence.APACHE,
        "https://github.com/androidx/media",
        listOf(
            "androidx.media3:media3-exoplayer",
            "androidx.media3:media3-exoplayer-hls",
            "androidx.media3:media3-exoplayer-dash",
            "androidx.media3:media3-ui-compose",
            "androidx.media3:media3-ui",
            "androidx.media3:media3-datasource-okhttp"
        )
    ),
    OpenSourceNotice(
        "Jellyfin Media3 FFmpeg decoder",
        "1.8.0+1",
        R.string.settings_lib_ffmpeg,
        Licence.GPL3,
        "https://github.com/jellyfin/jellyfin-androidx-media",
        listOf("org.jellyfin.media3:media3-ffmpeg-decoder")
    ),
    OpenSourceNotice(
        "Jetpack Compose",
        "BOM 2025.07.00",
        R.string.settings_lib_compose,
        Licence.APACHE,
        "https://developer.android.com/jetpack/androidx/releases/compose",
        listOf("androidx.compose.foundation:foundation", "androidx.compose.ui:ui", "androidx.compose.ui:ui-tooling-preview")
    ),
    OpenSourceNotice(
        "Compose for TV",
        "1.0.1",
        R.string.settings_lib_tv,
        Licence.APACHE,
        "https://developer.android.com/jetpack/androidx/releases/tv",
        listOf("androidx.tv:tv-material")
    ),
    OpenSourceNotice(
        "AndroidX Activity",
        "1.10.1",
        R.string.settings_lib_activity,
        Licence.APACHE,
        "https://developer.android.com/jetpack/androidx/releases/activity",
        listOf("androidx.activity:activity-compose")
    ),
    OpenSourceNotice(
        "AndroidX Lifecycle",
        "2.9.2",
        R.string.settings_lib_lifecycle,
        Licence.APACHE,
        "https://developer.android.com/jetpack/androidx/releases/lifecycle",
        listOf("androidx.lifecycle:lifecycle-viewmodel-compose", "androidx.lifecycle:lifecycle-runtime-compose")
    ),
    OpenSourceNotice(
        "AndroidX Room",
        "2.7.2",
        R.string.settings_lib_room,
        Licence.APACHE,
        "https://developer.android.com/jetpack/androidx/releases/room",
        listOf("androidx.room:room-runtime", "androidx.room:room-ktx", "androidx.room:room-paging")
    ),
    OpenSourceNotice(
        "AndroidX Paging",
        "3.3.6",
        R.string.settings_lib_paging,
        Licence.APACHE,
        "https://developer.android.com/jetpack/androidx/releases/paging",
        listOf("androidx.paging:paging-compose")
    ),
    OpenSourceNotice(
        "AndroidX WorkManager",
        "2.10.2",
        R.string.settings_lib_work,
        Licence.APACHE,
        "https://developer.android.com/jetpack/androidx/releases/work",
        listOf("androidx.work:work-runtime-ktx")
    ),
    OpenSourceNotice(
        "AndroidX DataStore",
        "1.1.7",
        R.string.settings_lib_datastore,
        Licence.APACHE,
        "https://developer.android.com/jetpack/androidx/releases/datastore",
        listOf("androidx.datastore:datastore-preferences")
    ),
    OpenSourceNotice(
        "Coil",
        "3.3.0",
        R.string.settings_lib_coil,
        Licence.APACHE,
        "https://github.com/coil-kt/coil",
        listOf("io.coil-kt.coil3:coil-compose", "io.coil-kt.coil3:coil-network-okhttp")
    ),
    OpenSourceNotice(
        "OkHttp",
        "4.12.0",
        R.string.settings_lib_okhttp,
        Licence.APACHE,
        "https://github.com/square/okhttp",
        listOf("com.squareup.okhttp3:okhttp")
    ),
    OpenSourceNotice(
        "Kotlin coroutines",
        "1.10.2",
        R.string.settings_lib_coroutines,
        Licence.APACHE,
        "https://github.com/Kotlin/kotlinx.coroutines",
        listOf("org.jetbrains.kotlinx:kotlinx-coroutines-core", "org.jetbrains.kotlinx:kotlinx-coroutines-android")
    ),
    OpenSourceNotice(
        "Kotlin serialization",
        "1.9.0",
        R.string.settings_lib_serialization,
        Licence.APACHE,
        "https://github.com/Kotlin/kotlinx.serialization",
        listOf("org.jetbrains.kotlinx:kotlinx-serialization-json")
    ),
    OpenSourceNotice(
        "ZXing",
        "3.5.3",
        R.string.settings_lib_zxing,
        Licence.APACHE,
        "https://github.com/zxing/zxing",
        listOf("com.google.zxing:core")
    ),
    OpenSourceNotice("Kotlin", "2.2.0", R.string.settings_lib_kotlin, Licence.APACHE, "https://github.com/JetBrains/kotlin"),
    OpenSourceNotice("Guava", null, R.string.settings_lib_guava, Licence.APACHE, "https://github.com/google/guava"),
    OpenSourceNotice(
        "Nunito",
        null,
        R.string.settings_lib_nunito,
        Licence.OFL,
        "https://github.com/googlefonts/nunito"
    )
)

/** Every library in the app, its version and licence; each opens its project's page as a QR code. */
@Composable
fun LicencesScreen() {
    var shown by remember { mutableStateOf<OpenSourceNotice?>(null) }
    SettingsPage(stringResource(R.string.settings_licences_title)) {
        item {
            Muted(stringResource(R.string.settings_licences_intro), maxLines = 2)
        }
        itemsIndexed(openSourceNotices) { i, notice ->
            SettingRow(
                notice.title,
                "${notice.licence.title} · ${stringResource(notice.use)}",
                { shown = notice },
                if (i ==
                    0
                ) {
                    rememberFirstFocus()
                } else {
                    Modifier
                }
            )
        }
    }
    shown?.let { notice ->
        LinkPanel(
            notice.name,
            notice.url,
            stringResource(R.string.settings_licence_link, notice.licence.title, notice.licence.url.removePrefix("https://"))
        ) {
            shown =
                null
        }
    }
}
