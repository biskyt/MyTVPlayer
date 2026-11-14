package com.biskyt.mytvplayer.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent

/**
 * What a launcher card opens: an intent for the app's activity, with the app's own words for what to open ([values],
 * such as a kind and ids). Kept as plain text extras, which a card's intent address can carry.
 */
object LauncherLink {
    private const val PREFIX = "launcher."

    private fun action(context: Context) = "${context.packageName}.action.OPEN_FROM_LAUNCHER"

    fun intent(context: Context, activity: Class<out Activity>, values: Map<String, String>): Intent =
        Intent(context, activity).setAction(action(context)).apply { values.forEach { (k, v) -> putExtra(PREFIX + k, v) } }

    /** The values of a card's [intent]; null for another intent. */
    fun read(context: Context, intent: Intent): Map<String, String>? {
        if (intent.action != action(context)) return null
        val extras = intent.extras ?: return emptyMap()
        return extras.keySet().filter {
            it.startsWith(PREFIX)
        }.mapNotNull { k -> extras.getString(k)?.let { k.removePrefix(PREFIX) to it } }.toMap()
    }
}
