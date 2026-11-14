package com.biskyt.mytvplayer.launcher

import android.content.Intent

// The TV's home screen (the launcher), the owner 2026-11-14: Continue watching in its Play Next row, and the favourite
// channels as a channel (a row) of their own. Off unless the user turns it on. This package knows nothing of the app's
// data, so that MyPlexPlayer can use it as it is: each app gives it the cards (see LauncherHost).

/** What the launcher shows: the Play Next cards, and the cards of the app's one channel (row). */
data class LauncherRows(
    val playNext: List<PlayNextCard>,
    /** The channel's name, in the app's language. */
    val channelName: String,
    val channel: List<ChannelCard>,
    /** What the channel's own link opens (the app, on its list of those channels). */
    val channelOpen: Intent,
)

/** A film or an episode, part watched or up next, for the launcher's Play Next row (Android TV's Watch Next). */
data class PlayNextCard(
    /** The app's own id for it, unique and the same each time: the launcher's row is matched to it. */
    val key: String,
    /** The film's title, or the series' name. */
    val title: String,
    /** For an episode: its numbers and title. */
    val episode: Episode?,
    /** An episode not started yet (the next one), not a film or episode part watched. */
    val upNext: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    /** When it was watched last: the launcher puts the newest first. */
    val watchedAt: Long,
    /** Its poster (portrait); the app serves it to the launcher (see LauncherImages). */
    val posterUrl: String?,
    /** What it opens in the app. */
    val open: Intent,
) {
    data class Episode(val season: Int?, val number: Int?, val title: String?)
}

/** A card on the app's channel: a TV channel, with its logo. */
data class ChannelCard(val key: String, val name: String, val logoUrl: String?, val open: Intent)

/**
 * The app's side of the launcher rows: the Application implements it. [launcherImage] downloads a poster or logo the
 * way the app does (its proxy, its User-Agent); null if it can't. [republishLauncher] writes the rows again.
 */
interface LauncherHost {
    fun launcherImage(url: String): ByteArray?

    fun republishLauncher()
}
