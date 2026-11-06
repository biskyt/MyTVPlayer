package com.biskyt.mytvplayer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.core.net.ProxyCheck
import com.biskyt.mytvplayer.core.net.ProxySettings
import com.biskyt.mytvplayer.core.net.ProxyType
import com.biskyt.mytvplayer.core.net.PublicIp
import com.biskyt.mytvplayer.data.db.PlaylistEntity
import com.biskyt.mytvplayer.data.db.proxy
import com.biskyt.mytvplayer.data.db.withProxy
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.StatusPanel
import com.biskyt.mytvplayer.ui.common.TvCard
import com.biskyt.mytvplayer.ui.common.TvTextField
import com.biskyt.mytvplayer.ui.common.WorkRunner
import com.biskyt.mytvplayer.ui.common.WorkStatus
import com.biskyt.mytvplayer.ui.common.formatDate
import com.biskyt.mytvplayer.ui.common.formatDateTime
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import com.biskyt.mytvplayer.ui.theme.LocalAccent
import com.biskyt.mytvplayer.ui.theme.Palette
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistsViewModel(private val c: AppContainer) : ViewModel() {
    val state = combine(c.playlists.observePlaylists(), c.settings.settings) { list, s -> list to s.activePlaylistId }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList<PlaylistEntity>() to 0L)

    fun activate(p: PlaylistEntity, then: () -> Unit) = viewModelScope.launch {
        if (p.id != state.value.second) {
            c.settings.update { it.copy(activePlaylistId = p.id) }
            c.sync.switchTo(p.id)
        }
        then()
    }

    private val work = WorkRunner(c.appScope, viewModelScope, c.sync.state)

    /** Progress and the outcome of the last update from this page. */
    val status: WorkStatus? get() = work.status

    /** Downloads the playlist's channels, guide, movies and series again: its progress, then how it went. */
    fun update(p: PlaylistEntity) = work.run(appString(R.string.settings_playlist_updating, p.name)) {
        c.sync.refreshNow(p.id).fold(
            { guideErrors ->
                if (guideErrors.isEmpty()) {
                    WorkStatus(appString(R.string.settings_playlist_updated, p.name), appString(R.string.settings_playlist_updated_detail))
                } else {
                    // As on the TV guide page: the channels updated, but say which guide didn't.
                    WorkStatus(
                        appString(R.string.settings_playlist_updated_guides_failed, p.name),
                        guideErrors.values.first(),
                        failed = true
                    )
                }
            },
            { WorkRunner.failed(appString(R.string.settings_playlist_update_failed, p.name), it) }
        )
    }

    /** Adding the free channels, films and series: true while downloading; [freeError] if it failed. */
    var addingFree by mutableStateOf(false)
        private set
    var freeError by mutableStateOf<String?>(null)
        private set

    /** Sets up the free channels, films and series (as on first launch), then [then] gets the new playlist's id. */
    fun addFree(then: (Long) -> Unit) {
        if (addingFree) return
        addingFree = true
        freeError = null
        viewModelScope.launch {
            val added = c.playlists.addFree()
            addingFree = false
            added.onSuccess(then).onFailure { freeError = it.message }
        }
    }
}

@Composable
fun PlaylistsScreen() {
    val vm = appViewModel { PlaylistsViewModel(it) }
    val nav = LocalNavigator.current
    val (playlists, active) = vm.state.collectAsState().value
    val accent = LocalAccent.current
    SettingsPage(stringResource(R.string.settings_playlists_title)) {
        // Above the playlists, so "Update now" on any of them shows its progress and outcome in the same place.
        item(key = "status") { StatusPanel(vm.status) }
        items(playlists.size) { i ->
            val p = playlists[i]
            val isActive = p.id == active
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TvCard(
                    onClick = { vm.activate(p) { nav.reset(Screen.Home) } },
                    selected = isActive,
                    modifier = (if (i == 0) rememberFirstFocus(once = true) else Modifier).weight(1f)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(if (isActive) accent.color else INACTIVE_DISC),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(p.name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = Palette.background)
                        }
                        Column(Modifier.padding(start = 14.dp).weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleMedium)
                            Muted(playlistDetails(p))
                            expiry(p.expiresAt)?.let {
                                Text(
                                    expiryText(it),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (it.soon) Palette.warning else Palette.textMuted
                                )
                            }
                        }
                        if (isActive) {
                            Text(
                                stringResource(R.string.settings_playlist_in_use),
                                style = MaterialTheme.typography.labelMedium,
                                color = accent.color
                            )
                        }
                    }
                }
                // Only the playlist in use is updated (see SyncManager).
                if (isActive) PillButton(stringResource(R.string.settings_playlist_update_now), { vm.update(p) })
                PillButton(stringResource(R.string.settings_playlist_edit), { nav.push(Screen.PlaylistEdit(p.id)) })
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(stringResource(R.string.settings_playlist_add), {
                    nav.push(Screen.PlaylistEdit(null))
                }, if (playlists.isEmpty()) rememberFirstFocus() else Modifier, primary = playlists.isEmpty())
                // The free channels can be typed in as an M3U address too, but that's a long one to enter with a remote.
                if (playlists.none { it.isFree }) {
                    PillButton(
                        stringResource(
                            if (vm.addingFree) R.string.settings_playlist_downloading_free else R.string.settings_playlist_add_free
                        ),
                        // First-run style: Continue downloads the channels and goes to Home.
                        { vm.addFree { id -> nav.push(Screen.Categories(id, firstRun = true)) } }
                    )
                }
            }
        }
        vm.freeError?.let { item { Text(it, style = MaterialTheme.typography.bodyLarge, color = Palette.warning) } }
        item { Muted(stringResource(R.string.settings_playlist_select_hint)) }
    }
}

/** How near a provider's expiry date is: past, today, within [EXPIRY_WARNING_DAYS] days, or later. */
internal enum class ExpiryKind { EXPIRED, TODAY, SOON, LATER }

/** A provider's expiry time ([atMs]), [days] away in calendar days. */
internal data class Expiry(val kind: ExpiryKind, val atMs: Long, val days: Int) {
    /** Close or past: shown as a warning. */
    val soon: Boolean get() = kind != ExpiryKind.LATER
}

/**
 * The provider's expiry date (seconds), and how near it is. Null when there's none: free playlists, and providers
 * that don't say.
 */
internal fun expiry(expiresAtSec: Long?, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Expiry? {
    val at = expiresAtSec?.takeIf { it > 0 }?.times(1000) ?: return null

    // Calendar days, as the date shown: tomorrow is "in 1 day" at any time of day.
    fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(day(now), day(at)).toInt()
    val kind = when {
        at <= now -> ExpiryKind.EXPIRED
        days < 1 -> ExpiryKind.TODAY
        days <= EXPIRY_WARNING_DAYS -> ExpiryKind.SOON
        else -> ExpiryKind.LATER
    }
    return Expiry(kind, at, days)
}

private const val EXPIRY_WARNING_DAYS = 14

/** "Expires 12 Mar 2027", "Expires 12 Mar 2027, in 5 days" and so on. */
@Composable
private fun expiryText(e: Expiry): String {
    val date = formatDate(e.atMs)
    return when (e.kind) {
        ExpiryKind.EXPIRED -> stringResource(R.string.settings_playlist_expired, date)
        ExpiryKind.TODAY -> stringResource(R.string.settings_playlist_expires_today, date)
        ExpiryKind.SOON -> pluralStringResource(R.plurals.settings_playlist_expires_in, e.days, date, e.days)
        ExpiryKind.LATER -> stringResource(R.string.settings_playlist_expires, date)
    }
}

private fun playlistDetails(p: PlaylistEntity): String {
    val parts = mutableListOf(if (p.isM3u) "M3U" else "Xtream")
    if (p.lastSyncAt > 0) parts += appString(R.string.common_updated_at, formatDateTime(p.lastSyncAt))
    return parts.joinToString(" · ")
}

/** A line under the playlist form: [ok] (it worked) shows it in the accent colour, else as a warning. */
data class FormMessage(val text: String, val ok: Boolean = false)

class PlaylistEditViewModel(private val c: AppContainer, private val id: Long?) : ViewModel() {
    var m3u by mutableStateOf(false)
    var name by mutableStateOf("")
    var server by mutableStateOf("")
    var username by mutableStateOf("")
    var password by mutableStateOf("")

    /** M3U: optional movie and series playlist addresses. */
    var moviesUrl by mutableStateOf("")
    var seriesUrl by mutableStateOf("")

    /** The playlist's proxy: null for none. Its port is text while it is typed. */
    var proxyType by mutableStateOf<ProxyType?>(null)
    var proxyHost by mutableStateOf("")
    var proxyPort by mutableStateOf("")
    var proxyUsername by mutableStateOf("")
    var proxyPassword by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<FormMessage?>(null)
        private set
    private var original: PlaylistEntity? = null

    init {
        if (id != null) {
            viewModelScope.launch {
                c.playlists.get(id)?.let {
                    original = it
                    m3u = it.isM3u
                    name = it.name
                    server = it.server
                    username = it.username
                    password = it.password
                    moviesUrl = it.moviesUrl.orEmpty()
                    seriesUrl = it.seriesUrl.orEmpty()
                    proxyType = ProxyType.entries.firstOrNull { t -> t.name == it.proxyType }
                    proxyHost = it.proxyHost.orEmpty()
                    proxyPort = it.proxyPort?.toString().orEmpty()
                    proxyUsername = it.proxyUsername.orEmpty()
                    proxyPassword = it.proxyPassword.orEmpty()
                }
            }
        }
    }

    private fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: IOException) {
                message = FormMessage(e.message ?: appString(R.string.common_something_went_wrong))
            } finally {
                busy = false
            }
        }
    }

    /** The proxy as typed: null for none. Throws, with a message for the user, when it is not complete. */
    private fun proxy(): ProxySettings? {
        val type = proxyType ?: return null
        return ProxySettings.typed(type, proxyHost, proxyPort, proxyUsername, proxyPassword)
            .takeIf { it.valid } ?: throw IOException(appString(R.string.settings_proxy_bad))
    }

    /** Asks for the public address without the proxy and through it, and says what that shows. */
    fun checkProxy() = run {
        val proxy = proxy() ?: return@run
        message = FormMessage(appString(R.string.settings_proxy_checking), ok = true)
        message = when (val result = c.playlists.checkProxy(proxy)) {
            is ProxyCheck.Working -> FormMessage(
                result.direct?.let { appString(R.string.settings_proxy_works, address(result.via), address(it)) }
                    ?: appString(R.string.settings_proxy_works_only, address(result.via)),
                ok = true
            )
            is ProxyCheck.SameAddress -> FormMessage(appString(R.string.settings_proxy_same, address(result.ip)))
            is ProxyCheck.Failed -> FormMessage(appString(R.string.settings_proxy_failed, result.reason))
        }
    }

    private fun address(ip: PublicIp) = ip.country?.let { appString(R.string.common_ip_country, ip.ip, it) } ?: ip.ip

    fun test() = run {
        c.playlists.testConnection(m3u, server, username, password, proxy())
        message = FormMessage(appString(R.string.settings_playlist_connected), ok = true)
    }

    /** Saves; [onSaved] gets the playlist id. Changing login details re-downloads the channels. */
    fun save(onSaved: (Long) -> Unit) = run {
        val old = original
        val proxy = proxy()
        if (old == null) {
            message = FormMessage(appString(if (m3u) R.string.settings_playlist_downloading else R.string.settings_playlist_checking))
            val newId = if (m3u) {
                c.playlists.addM3u(
                    name,
                    server,
                    moviesUrl,
                    seriesUrl,
                    proxy
                )
            } else {
                c.playlists.add(name, server, username, password, proxy)
            }
            message = null
            onSaved(newId)
        } else {
            // From what's stored now, not when the screen opened: a sync may have recorded something since.
            val current = c.playlists.get(old.id) ?: old
            val updated = current.copy(
                name = name.ifBlank {
                    old.name
                },
                server = server.trim(),
                username = username.trim(),
                password = password,
                moviesUrl = moviesUrl.trim().ifEmpty { null }.takeIf { old.isM3u },
                seriesUrl = seriesUrl.trim().ifEmpty { null }.takeIf { old.isM3u }
            ).withProxy(proxy)
            c.playlists.update(updated)
            val loginChanged = updated.server != old.server || updated.username != old.username || updated.password != old.password
            if (loginChanged || updated.proxy != old.proxy) {
                c.sync.refresh(old.id)
            } else if (updated.moviesUrl != old.moviesUrl || updated.seriesUrl != old.seriesUrl) {
                c.sync.refreshVod(old.id)
            }
            onSaved(old.id)
        }
    }

    /** [onDeleted] is told whether any playlist is left. */
    fun delete(onDeleted: (Boolean) -> Unit) = run {
        id?.let { c.playlists.delete(it) }
        onDeleted(c.playlists.allIds().isNotEmpty())
    }
}

@Composable
fun PlaylistEditScreen(id: Long?, firstRun: Boolean) {
    val vm = appViewModel(key = "edit-$id") { PlaylistEditViewModel(it, id) }
    val nav = LocalNavigator.current
    Column(
        // Scrolls: with the proxy's boxes, the form can be taller than a TV screen.
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 96.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            stringResource(
                when {
                    firstRun -> R.string.settings_welcome_title
                    id == null -> R.string.settings_playlist_add_title
                    else -> R.string.settings_playlist_edit_title
                }
            ),
            style = MaterialTheme.typography.headlineLarge
        )
        if (firstRun) Muted(stringResource(R.string.settings_playlist_first_run_hint))
        if (id == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(stringResource(R.string.settings_playlist_type_xtream), { vm.m3u = false }, primary = !vm.m3u)
                PillButton(stringResource(R.string.settings_playlist_type_m3u), { vm.m3u = true }, primary = vm.m3u)
            }
        }
        // The keyboard's Next key moves straight on to the next box, ready for typing.
        val nameEdit = rememberSaveable { mutableStateOf(false) }
        val serverEdit = rememberSaveable { mutableStateOf(false) }
        val userEdit = rememberSaveable { mutableStateOf(false) }
        val passwordEdit = rememberSaveable { mutableStateOf(false) }
        val moviesEdit = rememberSaveable { mutableStateOf(false) }
        val seriesEdit = rememberSaveable { mutableStateOf(false) }
        val proxyHostEdit = rememberSaveable { mutableStateOf(false) }
        // With a proxy, Next on the last box moves on to its address.
        val toProxy: (() -> Unit)? = if (vm.proxyType != null) ({ proxyHostEdit.value = true }) else null
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Field(stringResource(R.string.settings_playlist_name), Modifier.weight(1f)) {
                TvTextField(
                    vm.name,
                    { vm.name = it },
                    stringResource(R.string.settings_playlist_name_hint),
                    rememberFirstFocus().fillMaxWidth(),
                    editing = nameEdit,
                    onNext = { serverEdit.value = true }
                )
            }
            Field(
                stringResource(if (vm.m3u) R.string.settings_playlist_address else R.string.settings_playlist_server),
                Modifier.weight(1f),
                schemeOf = vm.server,
                onScheme = { vm.server = withScheme(vm.server, it) }
            ) {
                TvTextField(
                    vm.server,
                    { vm.server = it },
                    if (vm.m3u) "https://example.com/playlist.m3u" else "http://example.com:8080",
                    Modifier.fillMaxWidth(),
                    keyboardType = KeyboardType.Uri,
                    editing = serverEdit,
                    onNext = { (if (vm.m3u) moviesEdit else userEdit).value = true }
                )
            }
        }
        if (vm.m3u) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Field(
                    stringResource(R.string.settings_playlist_movies),
                    Modifier.weight(1f),
                    schemeOf = vm.moviesUrl,
                    onScheme = { vm.moviesUrl = withScheme(vm.moviesUrl, it) }
                ) {
                    TvTextField(
                        vm.moviesUrl,
                        { vm.moviesUrl = it },
                        "https://example.com/movies.m3u",
                        Modifier.fillMaxWidth(),
                        keyboardType = KeyboardType.Uri,
                        editing = moviesEdit,
                        onNext = { seriesEdit.value = true }
                    )
                }
                Field(
                    stringResource(R.string.settings_playlist_series),
                    Modifier.weight(1f),
                    schemeOf = vm.seriesUrl,
                    onScheme = { vm.seriesUrl = withScheme(vm.seriesUrl, it) }
                ) {
                    TvTextField(
                        vm.seriesUrl,
                        { vm.seriesUrl = it },
                        "https://example.com/series.m3u",
                        Modifier.fillMaxWidth(),
                        keyboardType = KeyboardType.Uri,
                        editing = seriesEdit,
                        onNext = toProxy
                    )
                }
            }
            Muted(stringResource(R.string.settings_playlist_m3u_vod_hint), maxLines = 2)
        }
        if (!vm.m3u) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Field(stringResource(R.string.settings_playlist_username), Modifier.weight(1f)) {
                    TvTextField(
                        vm.username,
                        { vm.username = it },
                        stringResource(R.string.settings_playlist_username),
                        Modifier.fillMaxWidth(),
                        autoCorrect = false,
                        editing = userEdit,
                        onNext = { passwordEdit.value = true }
                    )
                }
                Field(stringResource(R.string.settings_playlist_password), Modifier.weight(1f)) {
                    TvTextField(
                        vm.password,
                        { vm.password = it },
                        stringResource(R.string.settings_playlist_password),
                        Modifier.fillMaxWidth(),
                        autoCorrect = false,
                        editing = passwordEdit,
                        onNext = toProxy
                    )
                }
            }
        }
        ProxyFields(vm, proxyHostEdit)
        vm.message?.let {
            Text(it.text, style = MaterialTheme.typography.bodyLarge, color = if (it.ok) LocalAccent.current.color else Palette.warning)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(
                stringResource(
                    if (vm.busy) {
                        R.string.settings_playlist_wait
                    } else if (firstRun) {
                        R.string.common_continue
                    } else {
                        R.string.settings_playlist_save
                    }
                ),
                {
                    vm.save { pid ->
                        if (id == null) nav.replace(Screen.Categories(pid, firstRun = true)) else nav.pop()
                    }
                },
                primary = true
            )
            PillButton(stringResource(R.string.settings_playlist_test), vm::test)
            if (vm.proxyType != null) PillButton(stringResource(R.string.settings_proxy_check), vm::checkProxy)
            if (id != null) {
                Box(Modifier.width(40.dp))
                PillButton(stringResource(R.string.settings_playlist_delete), {
                    vm.delete { any -> nav.reset(if (any) Screen.Home else Screen.Welcome) }
                })
            }
        }
    }
}

/** The playlist's proxy: none, HTTP or SOCKS5, and its address, port and optional login. */
@Composable
private fun ProxyFields(vm: PlaylistEditViewModel, hostEdit: MutableState<Boolean>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_proxy_title), style = MaterialTheme.typography.titleSmall)
            listOf(
                null to R.string.settings_proxy_none,
                ProxyType.HTTP to R.string.settings_proxy_http,
                ProxyType.SOCKS to R.string.settings_proxy_socks
            )
                .forEach { (type, label) ->
                    PillButton(stringResource(label), { vm.proxyType = type }, selected = vm.proxyType == type, compact = true)
                }
        }
        if (vm.proxyType == null) return@Column
        Muted(stringResource(R.string.settings_proxy_help), maxLines = 2)
        val portEdit = rememberSaveable { mutableStateOf(false) }
        val userEdit = rememberSaveable { mutableStateOf(false) }
        val passwordEdit = rememberSaveable { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Field(stringResource(R.string.settings_proxy_host), Modifier.weight(2f)) {
                TvTextField(
                    vm.proxyHost,
                    { vm.proxyHost = it },
                    "proxy.example.com",
                    Modifier.fillMaxWidth(),
                    keyboardType = KeyboardType.Uri,
                    editing = hostEdit,
                    onNext = { portEdit.value = true }
                )
            }
            Field(stringResource(R.string.settings_proxy_port), Modifier.weight(1f)) {
                TvTextField(
                    vm.proxyPort,
                    { vm.proxyPort = it.filter(Char::isDigit).take(PORT_DIGITS) },
                    if (vm.proxyType == ProxyType.SOCKS) "1080" else "8080",
                    Modifier.fillMaxWidth(),
                    keyboardType = KeyboardType.Number,
                    editing = portEdit,
                    onNext = { userEdit.value = true }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Field(stringResource(R.string.settings_proxy_username), Modifier.weight(1f)) {
                TvTextField(
                    vm.proxyUsername,
                    { vm.proxyUsername = it },
                    stringResource(R.string.settings_proxy_username),
                    Modifier.fillMaxWidth(),
                    autoCorrect = false,
                    editing = userEdit,
                    onNext = { passwordEdit.value = true }
                )
            }
            Field(stringResource(R.string.settings_proxy_password), Modifier.weight(1f)) {
                TvTextField(
                    vm.proxyPassword,
                    { vm.proxyPassword = it },
                    stringResource(R.string.settings_proxy_password),
                    Modifier.fillMaxWidth(),
                    autoCorrect = false,
                    editing = passwordEdit
                )
            }
        }
    }
}

private const val PORT_DIGITS = 5

/**
 * A labelled box. For an address ([onScheme] given), "http://" and "https://" buttons beside the label put that start on
 * [schemeOf]: typing it on a TV remote is slow. The one the address starts with is shown selected.
 */
@Composable
private fun Field(
    label: String,
    modifier: Modifier,
    schemeOf: String? = null,
    onScheme: ((String) -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Palette.textMuted, modifier = Modifier.weight(1f))
            if (onScheme != null) {
                for (scheme in SCHEMES) {
                    PillButton(scheme, {
                        onScheme(scheme)
                    }, selected = schemeOf.orEmpty().trimStart().startsWith(scheme, ignoreCase = true), compact = true)
                }
            }
        }
        content()
    }
}

private val SCHEMES = listOf("http://", "https://")

/** [address] starting with [scheme] ("http://" or "https://"), in place of the start it had, if any. */
internal fun withScheme(address: String, scheme: String): String {
    val rest = address.trim()
    val old = SCHEMES.firstOrNull { rest.startsWith(it, ignoreCase = true) }
    return scheme + if (old == null) rest else rest.substring(old.length)
}

/** An inactive playlist's initial: a mid grey, darker than the text, so the active one's accent stands out. */
private val INACTIVE_DISC = Color(0xFF868E9A)
