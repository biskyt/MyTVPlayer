package com.biskyt.mytvplayer.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.tvprovider.media.tv.TvContractCompat

/**
 * A launcher asks for the app's cards (newly installed, or its data cleared): they are written again. Turned off (in
 * the manifest and by the app) while the rows are off. A card the user takes off needs nothing here: the TV provider
 * keeps that (see TvLauncher).
 */
class LauncherReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TvContractCompat.ACTION_INITIALIZE_PROGRAMS) (context.applicationContext as? LauncherHost)?.republishLauncher()
    }
}
