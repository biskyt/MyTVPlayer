package com.biskyt.mytvplayer.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.biskyt.mytvplayer.HomeRowsWorker
import com.biskyt.mytvplayer.launcher.setComponentsEnabled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * A launcher asks the app to refresh its rows on the TV's home screen ([action]; MyTVLauncher sends it as the TV wakes
 * and as its Home shows). It works while the app is not running. The work is done in the background (see
 * HomeRowsWorker), never on a schedule of its own (the owner, 2026-11-15).
 */
class HomeRowsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == action(context)) HomeRowsWorker.start(context)
    }

    companion object {
        fun action(context: Context) = "${context.packageName}.action.REFRESH_HOME_ROWS"

        /**
         * The receiver is on only while [needed] is true (it is off as installed). While it is off, Android does not
         * start the app for a request at all.
         */
        fun follow(context: Context, scope: CoroutineScope, needed: Flow<Boolean>) = scope.launch {
            needed.distinctUntilChanged().collect { context.setComponentsEnabled(it, HomeRowsReceiver::class.java) }
        }
    }
}
