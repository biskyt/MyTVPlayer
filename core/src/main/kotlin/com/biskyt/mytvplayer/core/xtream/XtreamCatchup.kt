package com.biskyt.mytvplayer.core.xtream

import com.biskyt.mytvplayer.core.PlainBase64
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A programme in a channel's catch-up listing. */
data class CatchupProgramme(
    val startMs: Long,
    val stopMs: Long,
    val title: String,
    val description: String?,
    /** Whether the provider can replay it. */
    val hasArchive: Boolean,
    /** The provider's clock offset from UTC at this programme (from its local start time), if it sent one. */
    val serverOffsetMs: Long? = null
)

/**
 * A channel's catch-up listing, by start time. The provider's clock offset is worked out from the listing itself (its
 * local start times beside the epoch ones), so timeshift addresses can be built for any moment without knowing the
 * server's time zone, and across a clock change.
 */
data class CatchupListing(val programmes: List<CatchupProgramme>) {
    /** The provider's offset from UTC at [t]: that of the programme then (or the nearest one). */
    fun serverOffsetAt(t: Long): Long =
        (programmes.lastOrNull { it.startMs <= t && it.serverOffsetMs != null } ?: programmes.firstOrNull { it.serverOffsetMs != null })
            ?.serverOffsetMs ?: 0
}

private val serverTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val timeshiftTime = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm")

/**
 * The `get_simple_data_table` response: `{"epg_listings": [...]}`. Titles and descriptions are base64, except from a
 * few providers that send plain text, told apart by the listing as a whole: a plain "Kids" is also valid base64, but
 * a plain listing has words with spaces between them, which base64 never has.
 */
internal fun JsonElement.toCatchupListing(): CatchupListing {
    val items = ((this as? JsonObject)?.get("epg_listings") as? JsonArray).orEmpty()
    val base64 = items.filterIsInstance<JsonObject>().flatMap { listOfNotNull(it.str("title"), it.str("description")) }
        .none { ' ' in it.trim() }
    fun text(s: String) = if (base64) decodeOrNull(s) ?: s else s
    val programmes = items.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val start = o.long("start_timestamp")?.times(1000) ?: return@mapNotNull null
        val stop = o.long("stop_timestamp")?.times(1000) ?: return@mapNotNull null
        CatchupProgramme(
            startMs = start,
            stopMs = stop,
            title = o.str("title")?.let(::text)?.trim().orEmpty(), // none: the app names it when shown
            description = o.str("description")?.let(::text)?.trim()?.ifEmpty { null },
            hasArchive = o.bool("has_archive"),
            serverOffsetMs = o.str("start")?.let { local -> serverLocalMs(local)?.minus(start) }
        )
    }
    // Providers sometimes list a programme twice.
    return CatchupListing(programmes.distinctBy { it.startMs }.sortedBy { it.startMs })
}

/** A server-local "2026-10-18 18:00:00" read as if it were UTC, so subtracting the real epoch gives the offset. */
private fun serverLocalMs(local: String): Long? =
    runCatching { LocalDateTime.parse(local.trim(), serverTime).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()

/** [s] decoded from base64, or null when it isn't base64 of text. */
private fun decodeOrNull(s: String): String? = runCatching { String(PlainBase64.decode(s.trim()), Charsets.UTF_8) }
    .getOrNull()?.takeIf { decoded -> decoded.none { it == '\uFFFD' || (it.isISOControl() && it != '\n' && it != '\r' && it != '\t') } }

/** The server-local "yyyy-MM-dd:HH-mm" a timeshift address starts at, for the moment [startMs]. */
internal fun timeshiftStart(startMs: Long, serverOffsetMs: Long): String =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(startMs + serverOffsetMs), ZoneOffset.UTC).format(timeshiftTime)
