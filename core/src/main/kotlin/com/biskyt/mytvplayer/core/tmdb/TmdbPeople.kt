package com.biskyt.mytvplayer.core.tmdb

import java.time.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// A cast or crew member's page from TMDB: who they are and their credits (MyPlexPlayer's PlexPeople.kt, fed from TMDB's
// person/{id} with its combined credits).

@Serializable
internal data class PersonJson(
    val id: Long = 0,
    val name: String = "",
    val biography: String? = null,
    val birthday: String? = null,
    val deathday: String? = null,
    @SerialName("place_of_birth") val placeOfBirth: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    @SerialName("known_for_department") val knownFor: String? = null,
    @SerialName("combined_credits") val credits: CombinedCreditsJson = CombinedCreditsJson()
)

@Serializable
internal data class CombinedCreditsJson(val cast: List<CreditJson> = emptyList(), val crew: List<CreditJson> = emptyList())

@Serializable
internal data class CreditJson(
    val id: Int = 0,
    /** "movie" or "tv". */
    @SerialName("media_type") val mediaType: String = "",
    /** A film's title. */
    val title: String? = null,
    /** A series' name. */
    val name: String? = null,
    /** A film's title in its own language ("The Green Mile", where [title] is the app's "La Ligne verte"). */
    @SerialName("original_title") val originalTitle: String? = null,
    /** A series' name in its own language. */
    @SerialName("original_name") val originalName: String? = null,
    val character: String? = null,
    val job: String? = null,
    val department: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null
)

/**
 * A person on TMDB. Any part can be missing: [biography] is often empty, and a writer can have no [born] or
 * [birthPlace]. [kinds] are their kinds of work, most credits first ([PersonCredit.ACTOR] and others).
 */
data class TmdbPerson(
    val id: Long,
    val name: String,
    val photoUrl: String?,
    val biography: String?,
    val born: LocalDate?,
    val birthPlace: String?,
    val died: LocalDate?,
    val kinds: List<String>,
    val credits: List<PersonCredit>
)

/**
 * One credit as a person's page lists it: [kind] is what they did (acted, directed, wrote or produced), [role] the part
 * played when TMDB names one (else the page names it by [kind]: TMDB's crew jobs are in English only), and [show] a
 * series rather than a film. [ratingKey] tells the title apart ("movie/603", "tv/1399": TMDB's films and series have
 * ids of their own). [originalTitle] is the title in its own language, when it differs from [title].
 */
data class PersonCredit(
    val ratingKey: String,
    val title: String,
    val show: Boolean,
    val year: Int?,
    val thumb: String?,
    val role: String?,
    val kind: String,
    /** What the credits are sorted by, newest first: the date ("2021-09-15"), or empty. */
    internal val date: String,
    val originalTitle: String? = null
) {
    companion object {
        const val ACTOR = "actor"
        const val DIRECTOR = "director"
        const val WRITER = "writer"
        const val PRODUCER = "producer"
    }
}

/** The kinds of credit that "All credits" lists, in this order. Other jobs (camera, music…) are left out. */
val ALL_CREDIT_KINDS = listOf(PersonCredit.ACTOR, PersonCredit.DIRECTOR, PersonCredit.WRITER, PersonCredit.PRODUCER)

/** The kinds of credit that "Also appears in" shows. */
val ELSEWHERE_KINDS = listOf(PersonCredit.ACTOR, PersonCredit.DIRECTOR, PersonCredit.WRITER)

/** A part as themselves (a talk show's guest, a documentary): left out, as MyPlexPlayer leaves out "appeared". */
private val SELF = Regex("""^(self|himself|herself|themselves)\b""", RegexOption.IGNORE_CASE)

private val PRODUCING = setOf("Producer", "Executive Producer")

/** What a crew job counts as: directing, writing or producing; null for any other job. */
private fun CreditJson.crewKind(): String? = when {
    job == "Director" -> PersonCredit.DIRECTOR
    department == "Writing" -> PersonCredit.WRITER
    job in PRODUCING -> PersonCredit.PRODUCER
    else -> null
}

private fun CreditJson.toCredit(kind: String, role: String?): PersonCredit? {
    val name = (title ?: this.name)?.trim()
    if (id <= 0 || name.isNullOrEmpty() || (mediaType != "movie" && mediaType != "tv")) return null
    val date = (releaseDate ?: firstAirDate)?.takeIf { it.length >= DATE_LENGTH }?.take(DATE_LENGTH).orEmpty()
    return PersonCredit(
        ratingKey = "$mediaType/$id",
        title = name,
        show = mediaType == "tv",
        year = date.take(YEAR_LENGTH).toIntOrNull(),
        thumb = image(posterPath, "w342"),
        role = role?.trim()?.ifEmpty { null },
        kind = kind,
        date = date,
        originalTitle = (originalTitle ?: originalName)?.trim()?.takeIf { it.isNotEmpty() && it != name }
    )
}

/**
 * The credits of [credits] of [kinds], newest first (those with no date last), each title once for each kind. Credits
 * of a kind come in [kinds]' order where their dates are the same.
 */
internal fun personCredits(credits: CombinedCreditsJson, kinds: List<String> = ALL_CREDIT_KINDS): List<PersonCredit> {
    val acted = credits.cast.filterNot { SELF.containsMatchIn(it.character.orEmpty()) }
        .mapNotNull { it.toCredit(PersonCredit.ACTOR, it.character) }
    // A crew credit has no part: the page names it by its kind, in the app's language (TMDB's jobs are English only).
    val crew = credits.crew.mapNotNull { c -> c.crewKind()?.let { c.toCredit(it, null) } }
    val all = acted + crew
    return kinds.flatMap { kind -> all.filter { it.kind == kind } }.distinctBy { it.kind to it.ratingKey }.sortedByDescending { it.date }
}

/**
 * "Also appears in": of a person's [credits] (as [personCredits] gives them), the titles they acted in, directed or
 * wrote that are not in the playlist (their keys not in [inLibrary]), newest first, each once, [max] at most.
 */
fun alsoAppearsIn(credits: List<PersonCredit>, inLibrary: Set<String>, max: Int = ELSEWHERE_MAX): List<PersonCredit> =
    credits.filter { it.kind in ELSEWHERE_KINDS && it.ratingKey !in inLibrary }.distinctBy { it.ratingKey }.take(max)

/**
 * A person's kinds of work (actor, director…), most credits first, [max] at most. TMDB's [knownFor] department
 * ("Acting", "Directing") comes first: it gives no count of each kind, as Plex does.
 */
fun kindsOfWork(credits: List<PersonCredit>, knownFor: String? = null, max: Int = KINDS_MAX): List<String> {
    val counted = credits.groupingBy { it.kind }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    val first = DEPARTMENTS[knownFor]?.takeIf { it in counted }
    return (listOfNotNull(first) + counted).distinct().take(max)
}

private val DEPARTMENTS = mapOf(
    "Acting" to PersonCredit.ACTOR,
    "Directing" to PersonCredit.DIRECTOR,
    "Writing" to PersonCredit.WRITER,
    "Production" to PersonCredit.PRODUCER
)

internal fun PersonJson.toPerson(): TmdbPerson {
    val credits = personCredits(credits)
    return TmdbPerson(
        id = id,
        name = name,
        photoUrl = image(profilePath, "h632"),
        biography = biography?.trim()?.ifEmpty { null },
        born = dateOf(birthday),
        birthPlace = placeOfBirth?.trim()?.ifEmpty { null },
        died = dateOf(deathday),
        kinds = kindsOfWork(credits, knownFor),
        credits = credits
    )
}

private const val DATE_LENGTH = 10
private const val YEAR_LENGTH = 4
private const val ELSEWHERE_MAX = 20
private const val KINDS_MAX = 2
