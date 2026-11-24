package com.biskyt.mytvplayer.ui.vod

import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.core.tmdb.TmdbCredits
import com.biskyt.mytvplayer.core.tmdb.TmdbEpisode
import com.biskyt.mytvplayer.core.tmdb.TmdbTitle
import com.biskyt.mytvplayer.core.vod.CastMember
import com.biskyt.mytvplayer.ui.common.ItemAction

// What a film's or show's More… offers (MyPlexPlayer's PlexDetailsParts.kt): its trailer, and from TMDB its people, a
// show's About and links. Without a TMDB key, only the provider's trailer.

/**
 * What a film's or show's More… shares: its trailer ([trailer] plays it, when it has one), Other trailers ([otherTrailers]
 * opens them, when TMDB has more than one), its cast and crew ([showCast],
 * where known), More like this ([showSimilar] opens it with the title's TMDB id), a show's About ([showAbout]; a film has
 * none, the owner, 2026-11-24) and its links in one row. A film's items have a help line under them, as its board draws
 * them. Only the trailer without [tmdb].
 */
@Suppress("LongParameterList") // one action for each option
fun titleActions(
    tmdb: TmdbTitle?,
    film: Boolean,
    trailer: (() -> Unit)?,
    otherTrailers: (() -> Unit)?,
    showCast: () -> Unit,
    showSimilar: (Int) -> Unit,
    showAbout: (() -> Unit)? = null
): List<ItemAction> {
    fun help(id: Int) = if (film) appString(id) else null
    val play = trailer?.let { ItemAction.Run(appString(R.string.vod_play_trailer), help(R.string.vod_play_trailer_help), run = it) }
    tmdb ?: return listOfNotNull(play)
    val crew = tmdb.cast.isNotEmpty() || tmdb.directors.isNotEmpty() || tmdb.writers.isNotEmpty()
    val others = otherTrailers?.takeIf { tmdb.trailers.size > 1 }
        ?.let { ItemAction.Run(appString(R.string.vod_other_trailers), help(R.string.vod_other_trailers_help), run = it) }
    return listOfNotNull(
        play,
        others,
        if (crew) ItemAction.Run(appString(R.string.vod_cast_crew), help(R.string.vod_cast_crew_help), run = showCast) else null,
        ItemAction.Run(appString(R.string.vod_similar), help(R.string.vod_similar_help)) { showSimilar(tmdb.id) },
        showAbout?.let { ItemAction.Run(appString(R.string.vod_about_this_show), run = it) },
        tmdb.links.takeIf { it.isNotEmpty() }?.let { ItemAction.Links(appString(R.string.vod_links), it) },
    )
}

/** The cast, then who directed and wrote it: everyone Cast and crew lists. */
fun castAndCrew(t: TmdbTitle?): List<CastMember> = t?.let {
    it.cast + it.directors.map { d -> d.copy(role = appString(R.string.vod_role_director)) } +
        it.writers.map { w -> w.copy(role = appString(R.string.vod_role_writer)) }
}.orEmpty()

/** An episode's own cast and crew: the show's cast in it, its guest stars, then who directed and wrote it. */
fun castAndCrew(c: TmdbCredits): List<CastMember> = c.cast + c.guestStars +
    c.directors.map { d -> d.copy(role = appString(R.string.vod_role_director)) } +
    c.writers.map { w -> w.copy(role = appString(R.string.vod_role_writer)) }

/** How many of [castAndCrew]'s first act in it: their role is their part. */
fun TmdbCredits.acting() = cast.size + guestStars.size

/** "Directed by Ana Ruiz · Written by Ana Ruiz, Paul Okafor", for an episode, where TMDB knows. */
fun episodePeople(e: TmdbEpisode): String? = people(TitleInfo(directors = e.directors.map { it.name }, writers = e.writers.map { it.name }))

/** "Cast and crew" for an episode's own people ([open] opens them); null while unknown, or when TMDB has none. */
fun episodeCastAction(credits: TmdbCredits?, open: () -> Unit): ItemAction? =
    credits?.takeUnless { it.isEmpty }?.let { ItemAction.Run(appString(R.string.vod_cast_crew), run = open) }

/**
 * An episode's options, then its show's, under "This episode" and "The show" (the series page and the player share
 * them, the owner, 2026-11-24). When one of the two has none, the other's alone, with no headings.
 */
fun episodeAndShowActions(episode: List<ItemAction>, show: List<ItemAction>): List<ItemAction> = if (show.isEmpty() || episode.isEmpty()) {
    episode + show
} else {
    listOf(ItemAction.Heading(appString(R.string.vod_this_episode))) + episode +
        ItemAction.Heading(appString(R.string.vod_the_show)) + show
}
