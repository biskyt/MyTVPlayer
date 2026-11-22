package com.biskyt.mytvplayer.core.m3u

import com.biskyt.mytvplayer.core.xtream.DEFAULT_ARCHIVE_DAYS
import com.biskyt.mytvplayer.core.xtream.timeshiftStart
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Catch-up for M3U channels, as playlists declare it on each channel: `catchup` (how the archive's address is made),
 * `catchup-days` and `catchup-source` (an address template). There's no listing of past programmes to ask for: the
 * past is the TV guide's programmes and hour slots (see CatchupFill); the archive plays from any time on.
 *
 * Types: `default` (the template is the archive's whole address), `append` (added to the channel's address),
 * `shift` (`?utc=…&lutc=…` added), `flussonic` (Flussonic's archive addresses) and `xc` (an Xtream panel's
 * timeshift address). Templates use the usual placeholders: `{utc}`/`{start}`, `{utcend}`/`{end}`, `{lutc}`/
 * `{now}`/`{timestamp}`, `{duration}` and `{offset}` (seconds, or `{duration:60}` for minutes), `{Y}{m}{d}{H}{M}{S}`
 * (the start, UTC), `{utc:Y-m-d H:M:S}`-style formats, and `${start}`-style spellings of the same.
 */
object M3uCatchup {
    /** The catch-up types this builds addresses for, under the names they're stored as. */
    private val aliases = mapOf("flussonic-hls" to "flussonic", "fs" to "flussonic", "timeshift" to "shift")
    private val types = setOf("default", "append", "shift", "flussonic", "flussonic-ts", "xc")

    private const val HOUR_MS = 3_600_000L

    /** Days kept when a playlist turns catch-up on without saying for how long (as for Xtream channels). */
    const val DEFAULT_DAYS = DEFAULT_ARCHIVE_DAYS

    /** The most days of catch-up offered, whatever a playlist says (the listing is made hour by hour). */
    private const val MAX_DAYS = 30

    /** How a channel's catch-up works, from its playlist attributes: null unless its archive's address can be made. */
    fun typeFor(type: String?, source: String?, url: String): String? {
        val t = type?.trim()?.lowercase()?.ifEmpty { null }?.let { aliases[it] ?: it } ?: return null
        return t.takeIf { it in types && url(url, it, source, HOUR_MS, 2 * HOUR_MS) != null }
    }

    /** The days of catch-up a channel offers: as its playlist says, within reason, or a week if it doesn't say. */
    fun daysFor(days: Int?): Int = days?.takeIf { it > 0 }?.coerceAtMost(MAX_DAYS) ?: DEFAULT_DAYS

    /** Whether the archive at [archiveUrl] is HLS, as far as its address tells (null: let the player find out). */
    fun isHls(archiveUrl: String, streamUrl: String, type: String): Boolean? = when {
        archiveUrl.substringBefore('?').endsWith(".m3u8") -> true
        type == "flussonic-ts" || archiveUrl.substringBefore('?').endsWith(".ts") -> false
        (type == "shift" || type == "append") && streamUrl.substringBefore('?').endsWith(".m3u8") -> true
        else -> null
    }

    /**
     * The archive address for watching [streamUrl] from [startMs] on to [nowMs]. Null when the address can't be made
     * (an address that isn't Flussonic's or an Xtream panel's, for those types).
     */
    fun url(streamUrl: String, type: String, source: String?, startMs: Long, nowMs: Long): String? {
        // A minute over, so the stream doesn't end just short of now.
        val endMs = nowMs + 60_000
        return when (type) {
            "default" -> fill(source?.ifBlank { null } ?: return null, startMs, endMs, nowMs)
            "append" -> appendTo(streamUrl, fill(source?.ifBlank { null } ?: return null, startMs, endMs, nowMs))
            "shift" -> appendTo(streamUrl, "?utc=${startMs / 1000}&lutc=${nowMs / 1000}")
            "flussonic", "flussonic-ts" -> flussonic(streamUrl, type, startMs, endMs)
            "xc" -> xtream(streamUrl, startMs, endMs)
            else -> null
        }
    }

    /** [extra] after [url]; a query in [extra] joins one [url] already has. */
    private fun appendTo(url: String, extra: String) = if (extra.startsWith("?") && '?' in url) url + "&" + extra.drop(1) else url + extra

    /** The start of the hour [nowMs] falls in, in the device's zone: the listing is the same until it ends. */
    fun currentHour(nowMs: Long): Long = hourStart(nowMs, ZoneId.systemDefault())

    /** The start of the hour [t] falls in, in [zone] (which matters where zones are offset by half an hour). */
    private fun hourStart(t: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(t).atZone(zone).withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli()

    // Braces escaped, even "}": Android's regex engine (ICU) rejects a bare one where Java's accepts it.
    private val placeholder = Regex("""\$?\{(utc|start|utcend|end|lutc|now|timestamp|duration|offset|[YmdHMS])(?::([^\}]*))?\}""")

    /** [template] with its placeholders filled in for watching from [startMs] to [endMs]. */
    internal fun fill(template: String, startMs: Long, endMs: Long, nowMs: Long): String = placeholder.replace(template) { m ->
        val name = m.groupValues[1]
        val arg = m.groupValues[2]
        val divisor = arg.toLongOrNull()?.takeIf { it > 0 } ?: 1
        when (name) {
            "duration" -> "${(endMs - startMs) / 1000 / divisor}"
            "offset" -> "${(nowMs - startMs) / 1000 / divisor}"
            "Y", "m", "d", "H", "M", "S" -> formatted(startMs, name)
            else -> {
                val t = when (name) {
                    "utcend", "end" -> endMs
                    "lutc", "now", "timestamp" -> nowMs
                    else -> startMs // utc, start
                }
                if (arg.isEmpty()) "${t / 1000}" else formatted(t, arg).replace(" ", "%20")
            }
        }
    }

    /** [t] (UTC) in a format written with Y, m, d, H, M and S for its parts, as in `{utc:Y-m-d H:M:S}`. */
    private fun formatted(t: Long, format: String): String {
        val time = Instant.ofEpochMilli(t).atOffset(ZoneOffset.UTC)
        return buildString {
            for (ch in format) {
                append(
                    when (ch) {
                        'Y' -> "%04d".format(time.year)
                        'm' -> "%02d".format(time.monthValue)
                        'd' -> "%02d".format(time.dayOfMonth)
                        'H' -> "%02d".format(time.hour)
                        'M' -> "%02d".format(time.minute)
                        'S' -> "%02d".format(time.second)
                        else -> ch.toString()
                    }
                )
            }
        }
    }

    /**
     * Flussonic's archive address: `…/name/index.m3u8` → `…/name/index-START-DURATION.m3u8` (HLS), or
     * `…/name/mpegts` → `…/name/timeshift_abs-START.ts`.
     */
    private fun flussonic(streamUrl: String, type: String, startMs: Long, endMs: Long): String? {
        val query = streamUrl.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
        val path = streamUrl.substringBefore('?')
        val slash = path.lastIndexOf('/').takeIf { it > path.indexOf("//") + 1 } ?: return null
        val base = path.substring(0, slash)
        val last = path.substring(slash + 1)
        val start = startMs / 1000
        val duration = (endMs - startMs) / 1000
        return when {
            last == "mpegts" || (type == "flussonic-ts" && last.isNotEmpty()) -> "$base/timeshift_abs-$start.ts$query"
            last.endsWith(".m3u8") -> "$base/${last.removeSuffix(".m3u8")}-$start-$duration.m3u8$query"
            else -> null // not an address Flussonic's archive is known to follow
        }
    }

    private val xtreamPath = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(\.\w+)?$""")

    private fun xtreamParts(url: String) = xtreamPath.find(url.substringBefore('?'))

    /**
     * An Xtream panel's timeshift address: `…/timeshift/USER/PASS/MINUTES/START/ID.ts`, as for Xtream logins. An M3U
     * doesn't say the panel's clock offset (a login's catch-up listing does), so the start is given in UTC.
     */
    private fun xtream(streamUrl: String, startMs: Long, endMs: Long): String? {
        val g = xtreamParts(streamUrl)?.groupValues ?: return null
        val minutes = ((endMs - startMs) / 60_000).coerceAtLeast(1)
        // Parts: host, user name, password, stream id. An M3U doesn't say the panel's clock offset: UTC.
        return "${g[1]}/timeshift/${g[2]}/${g[3]}/$minutes/${timeshiftStart(startMs, 0)}/${g[4]}.ts"
    }
}
