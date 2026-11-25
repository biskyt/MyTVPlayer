package com.biskyt.mytvplayer.testutil

import android.app.Application
import com.biskyt.mytvplayer.AppText
import com.biskyt.mytvplayer.ui.theme.autoFindsTv

/** A bare application (none of the app's wiring) whose code can still use the app's words. */
class PlainApp : Application() {
    override fun onCreate() {
        lookUpNioClasses() // before any image is loaded
        // The TV's screens, as Robolectric reports a touch screen: a test of the phone's sets it otherwise.
        autoFindsTv = { true }
        super.onCreate()
        AppText.init(this)
    }

    override fun onTerminate() {
        try {
            resetSettings(this)
        } finally {
            super.onTerminate()
        }
    }
}
