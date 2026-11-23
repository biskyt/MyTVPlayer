package com.biskyt.mytvplayer.core.vod

import java.util.Locale

/**
 * Where a download goes, under the device's Download folder (the owner, 2026-11-22): a film in "Movies/Dune (2021)/" as
 * "Dune (2021).mkv", an episode in "TV Shows/Line of Duty/Season 01/" as "Line of Duty S01E02.mkv" (a folder for each
 * season: the owner, 2026-11-23). Each title has a folder of its own. Characters that file systems refuse are left out.
 * The folder names stay in English in every language, as media servers such as Plex expect them.
 */
object DownloadNames {
    const val MOVIES = "Movies"
    const val TV_SHOWS = "TV Shows"

    /** A file's folder (below Download) and its name, with its extension. */
    data class Place(val folder: String, val file: String)

    fun movie(title: String, year: Int?, extension: String): Place {
        val name = safe(if (year != null) "$title ($year)" else title)
        return Place("$MOVIES/$name", "$name.${ext(extension)}")
    }

    fun episode(series: String, season: Int, episode: Int, extension: String): Place {
        val show = safe(series)
        // Latin digits in every language.
        val folder = "$TV_SHOWS/$show/Season %02d".format(Locale.ROOT, season)
        return Place(folder, "$show S%02dE%02d.${ext(extension)}".format(Locale.ROOT, season, episode))
    }

    // Braces escaped: Android's regex engine (ICU) rejects a bare one.
    private val refused = Regex("""[\\/:*?"<>|\u0000-\u001f]""")

    /** [name] without the characters file systems refuse, without dots at its ends, never empty. */
    private fun safe(name: String): String =
        name.replace(refused, " ").replace(Regex("""\s+"""), " ").trim().trim('.').trim().ifEmpty { "Untitled" }

    private fun ext(extension: String) = extension.trim().trimStart('.').lowercase().ifEmpty { "mp4" }
}
