package com.biskyt.mytvplayer.ui

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.openedFile
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Restoring from a file on a TV without Android's file picker. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class BackupPickerTest {
    @get:Rule val compose = createUiComposeRule<ComponentActivity>()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()
    private val app = ApplicationProvider.getApplicationContext<TestApp>()

    private val stub = ComponentName("com.android.tv.frameworkpackagestubs", "com.android.tv.frameworkpackagestubs.Stubs\$DocumentsStub")
    private val fileManager = ComponentName("com.lonelycatgames.Xplore", "com.lonelycatgames.Xplore.GetContent")

    // A TV maker's stand-in under a name of its own (the picker's request answered, but by nothing that can pick).
    private val unknownPicker = ComponentName("com.example.tv.system", "com.example.tv.system.NoPicker")
    private val androidPicker = ComponentName("com.android.documentsui", "com.android.documentsui.picker.PickActivity")

    private fun installs(component: ComponentName, action: String) {
        val pm = shadowOf(compose.activity.packageManager)
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(
            component,
            IntentFilter(action).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_OPENABLE)
                addDataType("*/*")
            }
        )
    }

    private fun showBackup() = compose.setContent {
        MyTvTheme { NavHost(remember { navigators.new(Screen.Backup) }) { ScreenContent(it) } }
    }

    private fun openPicker(): Intent {
        showBackup()
        compose.waitForText("Restore from a file…")
        // Not "Restore from Google Drive", which builds with a Google client also show.
        compose.onNode(hasText("Restore from a file…") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        return shadowOf(compose.activity).nextStartedActivityForResult.intent
    }

    @Test
    fun `a picker other than Android's own is passed over for the file managers`() {
        installs(unknownPicker, Intent.ACTION_OPEN_DOCUMENT)
        installs(fileManager, Intent.ACTION_GET_CONTENT)
        val started = openPicker()
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        @Suppress("DEPRECATION")
        assertEquals(Intent.ACTION_GET_CONTENT, started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!.action)
    }

    @Test
    fun `Android's own picker is used where the TV has it`() {
        installs(androidPicker, Intent.ACTION_OPEN_DOCUMENT)
        installs(fileManager, Intent.ACTION_GET_CONTENT)
        val started = openPicker()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.action)
        assertEquals(androidPicker.packageName, started.`package`)
    }

    @Test
    fun `a file opened with MyTVPlayer from a file manager is offered to restore`() {
        val file = android.net.Uri.parse("content://com.lonelycatgames.Xplore.FileContent/backup.json")
        assertEquals(file, Intent(Intent.ACTION_VIEW, file).openedFile())
        assertEquals(file, Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, file).openedFile())
        app.container.openedFile.value = file
        showBackup()
        compose.waitForText("Restore?")
        assertEquals(null, app.container.openedFile.value)
    }

    @Test
    fun `the TV's stand-in picker is skipped for a file manager`() {
        // Android TV's stand-in answers both kinds of request, and only says nothing can handle it.
        installs(stub, Intent.ACTION_OPEN_DOCUMENT)
        installs(stub, Intent.ACTION_GET_CONTENT)
        installs(fileManager, Intent.ACTION_GET_CONTENT)
        val started = openPicker()
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        @Suppress("DEPRECATION")
        val target = started.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_GET_CONTENT, target.action)
        @Suppress("DEPRECATION")
        val excluded = started.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS)!!.toList()
        assertTrue(excluded.toString(), stub in excluded)
    }
}
