package com.biskyt.mytvplayer.launcher

import android.content.Intent

// The TV's home screen (the launcher), the owner 2026-11-14: Continue watching in its Play Next row, and the app's own
// rows, each a channel on the launcher (MyTVPlayer's favourite channels, movies and series; the owner 2026-11-15). Off
// unless the user turns it on. This package knows nothing of the app's data, so that MyPlexPlayer can use it as it is:
// each app gives it the cards (see LauncherHost).

/**
 * What the launcher shows: the Play Next cards, and the app's rows, each a channel of the launcher, in this order. A row
 * not given now (one with nothing in it, such as favourites with none, or Live TV on a Plex server without it) is not
 * on the launcher: one made before is taken away, and the TV does not ask the user to show it.
 */
data class LauncherRows(val playNext: List<PlayNextCard>, val channels: List<LauncherChannel>)

/**
 * One of the app's rows: [key] names it, the same each time (a row is matched to it); its name (in the app's language),
 * its cards, and what its own link opens.
 */
data class LauncherChannel(val key: String, val name: String, val cards: List<RowCard>, val open: Intent)

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

/**
 * A card on one of the app's rows: a TV channel with its logo (wide), or a film, series or episode with its poster
 * (portrait), by [kind].
 */
data class RowCard(val key: String, val name: String, val imageUrl: String?, val open: Intent, val kind: Kind = Kind.CHANNEL) {
    /** (EPISODE is not used by MyTVPlayer: it is for MyPlexPlayer's recently added episodes.) */
    enum class Kind { CHANNEL, MOVIE, SERIES, EPISODE }
}

/** What a write did: the app's rows on the launcher now (their channel ids), and those made now (to show). */
data class Published(val channels: Set<Long>, val made: Set<Long>)

/**
 * The app's side of the launcher rows: the Application implements it. [launcherImage] downloads a poster or logo the
 * way the app does (its proxy, its User-Agent); null if it can't. [republishLauncher] writes the rows again.
 */
interface LauncherHost {
    fun launcherImage(url: String): ByteArray?

    fun republishLauncher()
}
