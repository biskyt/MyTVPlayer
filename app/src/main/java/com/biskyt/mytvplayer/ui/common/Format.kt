package com.biskyt.mytvplayer.ui.common

import com.biskyt.mytvplayer.AppText
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.ChannelProgramme
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale

fun formatTime(epochMs: Long): String = formats().time.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

fun formatRange(startMs: Long, stopMs: Long) = "${formatTime(startMs)} – ${formatTime(stopMs)}"

fun formatDateTime(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, AppText.locale).format(Date(epochMs))

/** A date written out in the app's language, e.g. "12 Mar 2027". */
fun formatDate(epochMs: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM, AppText.locale).format(Date(epochMs))

/** A language's name in the app's language, as a label ("fr" → "French", "Français" → "Anglais"), or [tag] when unknown. */
fun displayLanguage(tag: String): String =
    Locale.forLanguageTag(tag).getDisplayLanguage(AppText.locale).ifEmpty { tag }.replaceFirstChar { it.titlecase(AppText.locale) }

/**
 * A language's name in itself ("pt-BR" → "Português (Brasil)"), so it can be found whatever the app is showing;
 * without the country when [withCountry] is false ("Português").
 */
fun nativeLanguageName(tag: String, withCountry: Boolean = true): String = Locale.forLanguageTag(tag).let {
    (if (withCountry) it.getDisplayName(it) else it.getDisplayLanguage(it)).replaceFirstChar { c -> c.titlecase(it) }
}

// Day names are in the app's language (AppText.locale), not the device's. Built once per language.
private class DateFormats(val locale: Locale) {
    val time: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", locale)
    val day: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", locale)
    val date: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", locale)
}

@Volatile private var formats: DateFormats? = null

private fun formats(): DateFormats = formats?.takeIf { it.locale == AppText.locale } ?: DateFormats(AppText.locale).also { formats = it }

/** "Mon 19 Oct". */
val dateFormat: DateTimeFormatter get() = formats().date

/**
 * When something starts, relative to [now]: "Today 20:00", "Tomorrow 06:30", "Yesterday 22:00", "Wed 21:00" or
 * "Wed 3 Dec 21:00".
 */
fun formatDayTime(epochMs: Long, now: Long = System.currentTimeMillis()): String {
    val date = localDate(epochMs)
    val time = formatTime(epochMs)
    return when (val days = daysFrom(epochMs, now)) {
        0L -> appString(R.string.common_today_at, time)
        1L -> appString(R.string.common_tomorrow_at, time)
        -1L -> appString(R.string.common_yesterday_at, time)
        else -> "${(if (days in 2L..6L) formats().day else dateFormat).format(date)} $time"
    }
}

/** Which day [epochMs] falls on, relative to [now]: "Today", "Tomorrow", "Yesterday", "Wed" or "Wed 3 Dec". */
fun formatDay(epochMs: Long, now: Long = System.currentTimeMillis()): String = when (val days = daysFrom(epochMs, now)) {
    0L -> appString(R.string.common_today)
    1L -> appString(R.string.common_tomorrow)
    -1L -> appString(R.string.common_yesterday)
    else -> (if (days in 2L..6L) formats().day else dateFormat).format(localDate(epochMs))
}

/** Whether [epochMs] is on the same local day as [now]. */
fun isToday(epochMs: Long, now: Long = System.currentTimeMillis()): Boolean = daysFrom(epochMs, now) == 0L

private fun localDate(epochMs: Long) = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()

/** Local days from [now]'s day to [epochMs]'s (negative before it). */
private fun daysFrom(epochMs: Long, now: Long): Long = ChronoUnit.DAYS.between(localDate(now), localDate(epochMs))

fun formatCount(n: Int): String = "%,d".format(AppText.locale, n)

/** A playback position: "12:34" or "1:02:03". */
fun formatDuration(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val h = total / 3600
    val m = total / 60 % 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** A running time: "1 h 52 min" or "45 min". */
fun formatRuntime(seconds: Int): String {
    val minutes = (seconds + 30) / 60
    return when {
        minutes < 60 -> appString(R.string.common_runtime_min, minutes)
        minutes % 60 == 0 -> appString(R.string.common_runtime_h, minutes / 60)
        else -> appString(R.string.common_runtime_h_min, minutes / 60, minutes % 60)
    }
}

/** Channel number as shown in lists ("" when the provider gives none). */
val ChannelEntity.numberLabel: String get() = if (number > 0) number.toString() else ""

fun ChannelProgramme.isLive(nowMs: Long) = isLive(startMs, stopMs, nowMs)

fun isLive(startMs: Long, stopMs: Long, nowMs: Long) = startMs <= nowMs && stopMs > nowMs

/** The element [step] places after [current], wrapping around (the first element if [current] isn't in the list). */
fun <T> List<T>.cycle(current: T, step: Int = 1): T {
    val i = indexOf(current)
    return if (i < 0) first() else this[(i + step).mod(size)]
}

/** "S1 E2". */
fun shortEpisodeLabel(season: Int?, episode: Int?) = appString(R.string.player_short_episode_label, season.toString(), episode.toString())
