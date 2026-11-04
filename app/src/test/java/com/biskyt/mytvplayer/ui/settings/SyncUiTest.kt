package com.biskyt.mytvplayer.ui.settings

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performSemanticsAction
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.biskyt.mytvplayer.core.sync.GoogleAuth
import com.biskyt.mytvplayer.core.sync.SyncFile
import com.biskyt.mytvplayer.core.sync.SyncStore
import com.biskyt.mytvplayer.data.ProgressSync
import com.biskyt.mytvplayer.data.SyncAccount
import com.biskyt.mytvplayer.data.db.AppDatabase
import com.biskyt.mytvplayer.testutil.Navigators
import com.biskyt.mytvplayer.testutil.TestApp
import com.biskyt.mytvplayer.testutil.createUiComposeRule
import com.biskyt.mytvplayer.testutil.waitAdvancingClock
import com.biskyt.mytvplayer.testutil.waitForText
import com.biskyt.mytvplayer.ui.theme.MyTvTheme
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings › Sync between TVs: the code to type and its QR code, signing in, and stopping. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, qualifiers = "+w960dp-h540dp-tvdpi")
class SyncUiTest {
    @get:Rule val compose = createUiComposeRule()

    @get:Rule(order = Navigators.ORDER)
    val navigators = Navigators()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val folder = ConcurrentHashMap<String, String>()

    /** Google: the sign-in stays pending until [approved]. */
    @Volatile private var approved = false

    /** Google refuses the permissions (as it refused drive.appdata in 1.10.0). */
    @Volatile private var refuse = false
    private val google = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/device/code" && refuse -> MockResponse().setResponseCode(400).setBody("""{"error":"invalid_scope"}""")
                request.path == "/device/code" -> MockResponse().setBody(
                    """{"device_code":"d","user_code":"ABCD-EFGH","verification_url":"https://www.google.com/device","expires_in":600,"interval":0}"""
                )
                !approved -> {
                    Thread.sleep(PENDING_MS)
                    MockResponse().setResponseCode(428).setBody("""{"error":"authorization_pending"}""")
                }
                else -> {
                    val idToken =
                        "h." + Base64.getUrlEncoder().withoutPadding().encodeToString("""{"email":"me@example.com"}""".toByteArray()) + ".s"
                    MockResponse().setBody("""{"access_token":"a","expires_in":3600,"refresh_token":"r","id_token":"$idToken"}""")
                }
            }
        }
    }

    private val store = object : SyncStore {
        override suspend fun list() = folder.map { (id, name) -> SyncFile(id, name, "1") }

        override suspend fun download(id: String) = ByteArray(0)

        override suspend fun create(name: String, content: ByteArray) = "1".also { folder[it] = name }

        override suspend fun update(id: String, content: ByteArray) = Unit

        override suspend fun delete(id: String) {
            folder.remove(id)
        }
    }

    private val sync = ProgressSync(
        db,
        SyncAccount(
            PreferenceDataStoreFactory.create(scope = scope) {
                context.filesDir.resolve("sync-ui.preferences_pb").apply { delete() }
            }
        ),
        GoogleAuth(OkHttpClient(), "client", "secret", google.url("").toString().trimEnd('/')),
        scope,
        { store }
    )

    @After
    fun tearDown() {
        scope.cancel()
        google.shutdown()
        db.close()
    }

    private fun click(text: String) = compose.onNode(hasText(text) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)

    @Test
    fun `the code and its QR code show, signing in turns the sync on, and stopping deletes this TV's file`() {
        val vm = SyncViewModel(sync)
        compose.setContent { MyTvTheme { SyncScreen(vm) } }
        click("Sign in with Google")
        compose.waitForText("ABCD-EFGH")
        compose.waitForText("On your phone or computer, go to www.google.com/device and enter this code:")
        assertTrue(
            compose.onAllNodes(
                hasContentDescription("QR code for https://www.google.com/device?user_code=ABCD-EFGH")
            ).fetchSemanticsNodes().isNotEmpty()
        )

        approved = true
        compose.waitForText("Signed in as me@example.com")
        // The first sync sends this TV's file.
        compose.waitAdvancingClock { folder.isNotEmpty() }

        click("Stop syncing")
        compose.waitForText("Sign in with Google")
        assertTrue(folder.isEmpty())
    }

    @Test
    fun `cancel goes back to the sign-in button`() {
        val vm = SyncViewModel(sync)
        compose.setContent { MyTvTheme { SyncScreen(vm) } }
        click("Sign in with Google")
        compose.waitForText("ABCD-EFGH")
        click("Cancel")
        compose.waitForText("Sign in with Google")
    }

    @Test
    fun `a refused sign-in shows Google's reason`() {
        refuse = true
        val vm = SyncViewModel(sync)
        compose.setContent { MyTvTheme { SyncScreen(vm) } }
        click("Sign in with Google")
        compose.waitForText("Google refused the sign-in (invalid_scope).")
    }

    private companion object {
        const val PENDING_MS = 20L
    }
}
