package com.biskyt.mytvplayer.testutil

import android.app.Application
import com.biskyt.mytvplayer.AppText

/** A bare application (none of the app's wiring) whose code can still use the app's words. */
class PlainApp : Application() {
    override fun onCreate() {
        lookUpNioClasses() // before any image is loaded
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
