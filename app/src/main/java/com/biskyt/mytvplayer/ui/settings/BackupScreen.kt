package com.biskyt.mytvplayer.ui.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.core.sync.SyncException
import com.biskyt.mytvplayer.data.BackupException
import com.biskyt.mytvplayer.data.BackupFile
import com.biskyt.mytvplayer.data.DriveBackup
import com.biskyt.mytvplayer.data.describe
import com.biskyt.mytvplayer.ui.LocalNavigator
import com.biskyt.mytvplayer.ui.Screen
import com.biskyt.mytvplayer.ui.appViewModel
import com.biskyt.mytvplayer.ui.common.Muted
import com.biskyt.mytvplayer.ui.common.PillButton
import com.biskyt.mytvplayer.ui.common.formatDayTime
import com.biskyt.mytvplayer.ui.common.rememberFirstFocus
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BackupViewModel(private val c: AppContainer) : ViewModel() {
    /** Where a restore comes from. */
    sealed interface Source {
        data class File(val uri: Uri) : Source

        data class Drive(val saved: DriveBackup.Saved) : Source
    }

    private val file = c.backupFile
    private val drive = c.driveBackup

    /** Whether the app was built with a Google client: without one, there is no backup in Drive. */
    val driveAvailable: Boolean get() = c.progressSync.available

    val google = GoogleSignIn(c.progressSync, viewModelScope)

    /** The Google sign-in; null until it is read from storage. */
    val account = c.progressSync.account.state.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The backup in Drive, when one was found (or saved) while signed in. */
    var driveSaved by mutableStateOf<DriveBackup.Saved?>(null)
        private set

    val needsPermission: Boolean get() = file.needsPermission

    /** This install's backup file, if any. */
    var saved by mutableStateOf<BackupFile.Saved?>(null)
        private set

    /** What happened last, or what is happening. */
    var status by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    /** A backup waiting for the user to confirm restoring from it. */
    var confirming by mutableStateOf<Source?>(null)
        private set

    init {
        viewModelScope.launch { saved = runCatching { file.find() }.getOrNull() }
        // Quietly: a page that opens without a connection shows no error.
        if (driveAvailable) viewModelScope.launch { if (signedIn()) driveSaved = runCatching { drive.find() }.getOrNull() }
    }

    private suspend fun signedIn() = account.filterNotNull().first().signedIn

    fun backUp() = run(appString(R.string.settings_backup_saving)) {
        val s = file.write(c.backup.export())
        saved = s
        appString(R.string.settings_backup_saved_to, s.path)
    }

    fun ask(uri: Uri) = ask(Source.File(uri))

    private fun ask(source: Source) {
        confirming = source
        status = null
    }

    /** Signs in to Google first, when not signed in (the sync stays as it is). */
    private fun withGoogle(then: () -> Unit) {
        if (busy) return
        viewModelScope.launch { if (signedIn()) then() else google.start(turnOnSync = false, then) }
    }

    fun backUpToDrive() = withGoogle {
        run(appString(R.string.settings_backup_saving)) {
            driveSaved = drive.save(c.backup.export())
            appString(R.string.settings_backup_drive_saved)
        }
    }

    /** Finds the backup in Drive, then asks the user to confirm restoring from it. */
    fun askDrive() = withGoogle {
        run(appString(R.string.settings_backup_drive_looking)) {
            val found = drive.find()
            driveSaved = found
            if (found == null) return@run appString(R.string.settings_backup_drive_none)
            ask(Source.Drive(found))
            null
        }
    }

    /**
     * Files opened with MyTVPlayer from another app (see MainActivity), offered to restore like one chosen here; while
     * the page is showing, so one left under another screen doesn't take them unseen.
     */
    suspend fun takeOpenedFiles() = c.openedFile.filterNotNull().collect {
        c.openedFile.value = null
        ask(it)
    }

    fun cancel() {
        confirming = null
    }

    fun failed(message: String) {
        status = message
    }

    /** Android 9: whether the storage permission is being asked for by "Back up to a file" (else on opening the page). */
    private var backUpAfterPermission = false

    /** Android 9: asked already on opening the page (not again if the screen is rebuilt, e.g. the TV changes mode). */
    var askedOnOpening = false
        private set

    fun askingPermission(forBackUp: Boolean) {
        backUpAfterPermission = forBackUp
        if (!forBackUp) askedOnOpening = true
    }

    /**
     * Android 9: the answer to the storage permission. Granted, the backup file can be seen: one already there (e.g.
     * from before a reinstall) is offered to restore, never silently replaced; otherwise "Back up to a file" carries on.
     */
    fun permissionResult(granted: Boolean) {
        val backUpNext = backUpAfterPermission
        backUpAfterPermission = false
        // Declined on opening the page: fine for restoring from a file, so nothing to report until "Back up to a file".
        if (!granted) return if (backUpNext) failed(appString(R.string.settings_backup_permission_denied)) else Unit
        viewModelScope.launch {
            // Not knowing whether one is there, nothing is saved over it.
            val found = try {
                file.find()
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                return@launch failed(appString(R.string.settings_backup_find_failed, describe(e)))
            }
            saved = found
            when {
                !backUpNext -> Unit
                found != null -> status = appString(R.string.settings_backup_already_there)
                else -> backUp()
            }
        }
    }

    /** Replaces everything from the backup, then downloads the playlists and guides again. [done]: has playlists. */
    fun restore(done: (Boolean) -> Unit) {
        val source = confirming ?: return
        if (busy) return
        confirming = null
        run(appString(R.string.settings_backup_restoring)) {
            // In the app's scope: leaving the page mustn't stop a restore halfway.
            val restored = c.appScope.async {
                when (source) {
                    is Source.File -> file.read(source.uri) { c.backup.restore(it) }
                    is Source.Drive -> c.backup.restore(ByteArrayInputStream(drive.read(source.saved)))
                }
            }
            done(restored.await())
            null
        }
    }

    /** Runs one action at a time; any failure is shown on the page. */
    @Suppress("TooGenericExceptionCaught") // storage and MediaStore errors vary by TV; none may crash the app
    private fun run(working: String, block: suspend () -> String?) {
        if (busy) return
        busy = true
        status = working
        viewModelScope.launch {
            status = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException) {
                e.message
            } catch (e: SyncException) {
                driveProblem(e)
            } catch (e: Exception) {
                appString(R.string.settings_backup_failed, describe(e))
            }
            busy = false
        }
    }
}

private fun driveProblem(e: SyncException): String {
    val code = e.code
    return when {
        e.reason == SyncException.Reason.SIGNED_OUT -> appString(R.string.settings_backup_drive_signed_out)
        code != null -> appString(R.string.settings_backup_drive_error, code)
        else -> appString(R.string.sync_failed)
    }
}

/**
 * Android TV fills in for system apps a TV leaves out (like the file picker) with stand-ins that only say "no
 * application can handle this request" (e.g. com.android.tv.frameworkpackagestubs; makers may name theirs otherwise).
 */
private val ActivityInfo.isStandIn get() = "stub" in packageName.lowercase()

/** Android's own file picker (com.android.documentsui, or Google's com.google.android.documentsui). */
private val ActivityInfo.isAndroidPicker get() = packageName.endsWith(".documentsui")

/** The activities that would answer [intent] (as starting it would find them). */
private fun Context.handlers(intent: Intent) =
    packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo }

/** Opens the picker in the backup folder (where the TV's picker supports that). */
private class OpenBackup : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(DocumentsContract.EXTRA_INITIAL_URI, BackupFile.PICKER_START)
}

@Composable
fun BackupScreen() {
    val vm = appViewModel { BackupViewModel(it) }
    val nav = LocalNavigator.current
    // Android 9 only: the Download folder needs the storage permission, asked for on opening the page (and again by
    // "Back up to a file" if it was declined).
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), vm::permissionResult)
    fun askPermission(forBackUp: Boolean) {
        vm.askingPermission(forBackUp)
        permission.launch(BackupFile.STORAGE_PERMISSION)
    }
    // Asked for on opening, so a backup already here (e.g. from before a reinstall) shows, ready to restore.
    LaunchedEffect(Unit) { if (vm.needsPermission && !vm.askedOnOpening) askPermission(forBackUp = false) }
    val backUp = { if (vm.needsPermission) askPermission(forBackUp = true) else vm.backUp() }
    val context = LocalContext.current
    val chooserTitle = stringResource(R.string.settings_backup_choose_file)
    val noPicker = stringResource(R.string.settings_backup_no_picker)
    val chooseFile = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let(vm::ask)
    }
    fun pick() {
        // Android's own picker where the TV has it, opened in the backup folder. Anything else answering for it may be
        // a stand-in under another name, so then the file manager apps installed (e.g. X-plore) are offered instead.
        val open = OpenBackup().createIntent(context, arrayOf("*/*"))
        val picker = context.handlers(open).firstOrNull { it.isAndroidPicker }
        val get = Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
        val (standIns, fileManagers) = context.handlers(get).partition { it.isStandIn }
        val tries = listOfNotNull(
            picker?.let { Intent(open).setPackage(it.packageName) },
            Intent.createChooser(get, chooserTitle)
                .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, standIns.map { ComponentName(it.packageName, it.name) }.toTypedArray())
                .takeIf { fileManagers.isNotEmpty() },
            // Last, whatever else answers for the picker (a maker's own, perhaps), as before.
            open.takeIf { picker == null && context.handlers(open).any { !it.isStandIn } }
        )
        // The first that opens: should the picker fail after all, a file manager is next.
        val opened = tries.any {
            try {
                chooseFile.launch(it)
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
        }
        if (!opened) vm.failed(noPicker)
    }

    LaunchedEffect(vm) { vm.takeOpenedFiles() }

    SettingsPage(stringResource(R.string.settings_backup_title)) {
        item {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Muted(stringResource(R.string.settings_backup_intro), maxLines = touchLines(4, 6))
                val last = vm.saved?.let { s ->
                    listOfNotNull(s.modifiedAt.takeIf { it > 0 }?.let(::formatDayTime), s.path).joinToString(" · ")
                }
                Muted(
                    if (last != null) {
                        stringResource(R.string.settings_backup_last, last)
                    } else {
                        stringResource(R.string.settings_backup_none)
                    },
                    maxLines = 2
                )
            }
        }
        item {
            if (vm.confirming != null) {
                ConfirmRestore(vm) { hasPlaylists -> nav.reset(if (hasPlaylists) Screen.Home else Screen.Welcome) }
            } else {
                FileButtons(vm, backUp, ::pick)
            }
        }
        if (vm.driveAvailable && vm.confirming == null) item { DriveBackupPart(vm) }
        vm.status?.let { item { Text(it, style = MaterialTheme.typography.bodyLarge) } }
    }
}

/** Back up to a file, and restore from this TV's backup file or from another. */
@Composable
private fun FileButtons(vm: BackupViewModel, backUp: () -> Unit, pick: () -> Unit) {
    ButtonRow {
        PillButton(stringResource(R.string.settings_backup_now), backUp, rememberFirstFocus(), primary = true)
        val s = vm.saved
        if (s != null) PillButton(stringResource(R.string.settings_backup_restore), { vm.ask(s.uri) })
        val restoreFile = if (s == null) R.string.settings_backup_restore_file else R.string.settings_backup_restore_other_file
        PillButton(stringResource(restoreFile), pick)
    }
}

/** Asks before a restore replaces everything. [done]: has playlists. */
@Composable
private fun ConfirmRestore(vm: BackupViewModel, done: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.settings_backup_confirm), style = MaterialTheme.typography.titleMedium)
        ButtonRow {
            PillButton(stringResource(R.string.settings_backup_restore), { vm.restore(done) }, rememberFirstFocus(), primary = true)
            PillButton(stringResource(R.string.settings_backup_cancel), vm::cancel)
        }
    }
}

/** Back up to Google Drive and restore from it, with a sign-in first when needed. */
@Composable
private fun DriveBackupPart(vm: BackupViewModel) {
    val account by vm.account.collectAsState()
    Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Muted(stringResource(R.string.settings_backup_drive_intro), maxLines = touchLines(3, 5))
        when (val s = vm.google.state) {
            is GoogleSignIn.State.Code -> SignInCode(s.code, vm.google::cancel)
            else -> {
                if (account?.signedIn == true) {
                    val last = vm.driveSaved?.modifiedAt?.takeIf { it > 0 }?.let(::formatDayTime)
                    Muted(
                        last?.let { stringResource(R.string.settings_backup_drive_last, it) }
                            ?: stringResource(R.string.settings_backup_drive_none)
                    )
                }
                ButtonRow {
                    // After a sign-in panel, the remote comes back here.
                    val focus = if (vm.google.closed > 0) rememberFirstFocus(vm.google.closed) else Modifier
                    PillButton(stringResource(R.string.settings_backup_drive), vm::backUpToDrive, focus)
                    PillButton(stringResource(R.string.settings_backup_restore_drive), vm::askDrive)
                }
                (s as? GoogleSignIn.State.Failed)?.let { Text(signInProblem(it), style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
}
