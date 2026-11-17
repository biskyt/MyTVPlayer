package com.biskyt.mytvplayer.ui.vod

import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.core.tmdb.TmdbTitle
import com.biskyt.mytvplayer.core.vod.CastMember
import com.biskyt.mytvplayer.ui.common.ItemAction
import com.biskyt.mytvplayer.ui.common.favouriteLabel

// What a film's or show's More… offers from TMDB (MyPlexPlayer's PlexDetailsParts.kt): without a TMDB key, none of it.

/**
 * What a film's or show's More… shares: its cast and crew ([showCast], where known), About ([showAbout]) and its links
 * in one row. A film's items have a help line under them, as its board draws them. Nothing without [tmdb].
 */
fun titleActions(tmdb: TmdbTitle?, film: Boolean, showCast: () -> Unit, showAbout: () -> Unit): List<ItemAction> {
    tmdb ?: return emptyList()
    fun help(id: Int) = if (film) appString(id) else null
    val crew = tmdb.cast.isNotEmpty() || tmdb.directors.isNotEmpty() || tmdb.writers.isNotEmpty()
    return listOfNotNull(
        if (crew) ItemAction.Run(appString(R.string.vod_cast_crew), help(R.string.vod_cast_crew_help), showCast) else null,
        ItemAction.Run(
            appString(if (film) R.string.vod_about_this_film else R.string.vod_about_this_show),
            help(R.string.vod_about_film_help),
            showAbout
        ),
        tmdb.links.takeIf { it.isNotEmpty() }?.let { ItemAction.Links(appString(R.string.vod_links), it) },
    )
}

/** "Add to favourites", or for a [favourite], "Remove from favourites". */
fun favouriteAction(favourite: Boolean, setFavourite: () -> Unit) = ItemAction.Run(favouriteLabel(favourite), run = setFavourite)

/** The cast, then who directed and wrote it: everyone Cast and crew lists. */
fun castAndCrew(t: TmdbTitle?): List<CastMember> = t?.let {
    it.cast + it.directors.map { name -> CastMember(name, appString(R.string.vod_role_director), null) } +
        it.writers.map { name -> CastMember(name, appString(R.string.vod_role_writer), null) }
}.orEmpty()
