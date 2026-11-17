package com.biskyt.mytvplayer.data

import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.core.tmdb.PersonCredit
import com.biskyt.mytvplayer.core.tmdb.alsoAppearsIn
import com.biskyt.mytvplayer.core.vod.VodTitles
import com.biskyt.mytvplayer.data.db.CAT_MOVIE
import com.biskyt.mytvplayer.data.db.CAT_SERIES
import com.biskyt.mytvplayer.data.db.VodCard
import java.io.IOException
import java.time.LocalDate
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A cast or crew member's page: who they are, the titles of theirs in the playlist, and their other credits. [kinds] are
 * their kinds of work, most credits first ([PersonCredit.ACTOR] and others). Any part can be missing.
 */
data class PersonPage(
    val name: String,
    val photoUrl: String?,
    val kinds: List<String>,
    val born: LocalDate?,
    val birthPlace: String?,
    val died: LocalDate?,
    val biography: String?,
    val library: List<LibraryTitle>,
    val elsewhere: List<PersonCredit>,
    val credits: List<PersonCredit>,
)

/**
 * A title of the person's that is in the playlist: a film, or a [show] (by its [id], its page's). [role] is their part
 * in it, else their job ([kind]). Both are null when they are not known.
 */
data class LibraryTitle(
    val id: Long,
    val show: Boolean,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val role: String?,
    val kind: String?,
)

/**
 * People's pages (MyPlexPlayer's PersonRepository), from TMDB: who they are and their credits. The playlist stores no
 * TMDB ids for its titles, so a credit is in it when a film or series there has the same title (see [libraryOf]).
 * Nothing is saved: TMDB's answers are kept for days (see [TmdbRepository]).
 */
class PersonRepository(private val vod: VodRepository, private val tmdb: TmdbRepository) {
    /**
     * The page of person [personId] (their id on TMDB), with the titles of theirs in playlist [playlistId] that its lists
     * show now (restricted ones hidden while locked). Fails when TMDB cannot give it (no key, out of reach, or no such
     * person).
     */
    suspend fun page(playlistId: Long, personId: Long): PersonPage {
        val person = tmdb.person(personId) ?: throw IOException(appString(R.string.vod_person_failed))
        val movies = vod.cards(playlistId, CAT_MOVIE)
        val series = vod.cards(playlistId, CAT_SERIES)
        // Each title's key is worked out once, away from the main thread: a playlist can have tens of thousands.
        val library = withContext(Dispatchers.Default) { libraryOf(person.credits, movies, series) }
        return PersonPage(
            name = person.name,
            photoUrl = person.photoUrl,
            kinds = person.kinds,
            born = person.born,
            birthPlace = person.birthPlace,
            died = person.died,
            biography = person.biography,
            library = library.map { it.second }.distinctBy { it.show to it.id }.sortedByDescending { it.year ?: 0 },
            elsewhere = alsoAppearsIn(person.credits, library.mapTo(HashSet()) { it.first }),
            credits = person.credits,
        )
    }
}

/**
 * Of a person's [credits], those in the playlist ([movies] and [series]), each with its credit's key: a title there
 * with the same key (see VodTitles.key) as the credit's title, or its original title (TMDB's title is in the app's
 * language, the provider's often the original), and the same year, a year either way (providers' years are often one
 * out, and a series' year can be its first season's or its first episode's). One credit of each title.
 */
fun libraryOf(credits: List<PersonCredit>, movies: List<VodCard>, series: List<VodCard>): List<Pair<String, LibraryTitle>> {
    if (credits.isEmpty()) return emptyList()
    val films = movies.groupBy { VodTitles.key(it.title) }
    val shows = series.groupBy { VodTitles.key(it.title) }
    return credits.distinctBy { it.ratingKey }.mapNotNull { c ->
        val byKey = if (c.show) shows else films
        val card = listOfNotNull(c.title, c.originalTitle).asSequence()
            .flatMap { byKey[VodTitles.key(it)].orEmpty() }
            .firstOrNull { card -> card.year?.let { y -> c.year?.let { abs(y - it) <= YEAR_SLACK } } ?: true }
            ?: return@mapNotNull null
        c.ratingKey to LibraryTitle(card.id, c.show, card.title, card.year ?: c.year, card.imageUrl ?: c.thumb, c.role, c.kind)
    }
}

/** How many years a playlist's title may be from TMDB's. */
private const val YEAR_SLACK = 1
