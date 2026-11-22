package com.biskyt.mytvplayer.data

import android.util.LruCache
import com.biskyt.mytvplayer.R
import com.biskyt.mytvplayer.appString
import com.biskyt.mytvplayer.core.m3u.M3uCatchup
import com.biskyt.mytvplayer.core.xtream.CatchupFill
import com.biskyt.mytvplayer.core.xtream.CatchupListing
import com.biskyt.mytvplayer.core.xtream.CatchupProgramme
import com.biskyt.mytvplayer.data.db.ChannelEntity
import com.biskyt.mytvplayer.data.db.PlaylistEntity
import java.io.IOException
import java.text.DateFormat
import java.time.ZoneId
import java.util.Date

/**
 * Catch-up (replaying what a channel showed in the last few days), for channels with [ChannelEntity.archiveDays].
 * Xtream: the listing comes from the provider only when catch-up is used (the catch-up page, "Start over", the TV
 * guide scrolled into the past) and is kept briefly for the few channels used most recently. M3U: there's no listing
 * to ask for, and the archive's address is made from the playlist's (see M3uCatchup). Either way, the times the
 * provider does not list are filled with the TV guide's programmes ([guide]), and the rest with slots of an hour at
 * most ("Replay from 14:00"): the archive plays them, as other apps show (see CatchupFill; the owner, 2026-11-22).
 */
class CatchupRepository(
    private val playlists: PlaylistRepository,
    /** The TV guide's programmes on the channel between two times (none: only the provider's and slots). */
    private val guide: suspend (PlaylistEntity, ChannelEntity, Long, Long) -> List<CatchupProgramme> = { _, _, _, _ -> emptyList() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Cached(val listing: CatchupListing, val at: Long)

    private val cache = LruCache<String, Cached>(CACHE_CHANNELS)

    /** The channel's whole listing (past and coming programmes). Throws XtreamException when the provider fails. */
    suspend fun listing(p: PlaylistEntity, ch: ChannelEntity): CatchupListing {
        val key = "${p.id}:${ch.streamId}"
        if (p.isM3u) {
            // Kept until the hour changes, as the TV guide asks for it row by row as it scrolls.
            val hour = M3uCatchup.currentHour(clock())
            cache[key]?.takeIf { it.at == hour }?.let { return it.listing }
            return filled(p, ch, emptyList()).also { cache.put(key, Cached(it, hour)) }
        }
        cache[key]?.takeIf { clock() - it.at < MAX_AGE_MS }?.let { return it.listing }
        val provider = withUserXtreamErrors { playlists.client(p).catchupListing(ch.streamId) }
        return filled(p, ch, provider.programmes).also { cache.put(key, Cached(it, clock())) }
    }

    /** [provider]'s programmes, with the times it leaves out filled from the guide and with slots. */
    private suspend fun filled(p: PlaylistEntity, ch: ChannelEntity, provider: List<CatchupProgramme>): CatchupListing {
        val now = clock()
        val from = now - ch.archiveDays * DAY_MS
        val known = guide(p, ch, from, now).filter { it.startMs >= from }
        val time = DateFormat.getTimeInstance(DateFormat.SHORT)
        val slots = CatchupFill.fill(provider, known, from..now, ZoneId.systemDefault()) { start ->
            appString(R.string.live_catchup_replay_from, time.format(Date(start)))
        }
        return CatchupListing(slots)
    }

    /**
     * Programmes that can be replayed: started, and within the channel's catch-up days. Also those the provider lists
     * without its archive: other apps replay them, and the channel's archive covers those days.
     */
    fun playable(listing: CatchupListing, ch: ChannelEntity): List<CatchupProgramme> {
        val now = clock()
        val from = now - ch.archiveDays * DAY_MS
        return listing.programmes.filter { it.startMs < now && it.startMs >= from }
    }

    /** The programme on now, for "Start over". */
    fun onNow(listing: CatchupListing): CatchupProgramme? {
        val now = clock()
        return listing.programmes.firstOrNull { it.startMs <= now && it.stopMs > now }
    }

    /** The channel as it was from [startMs], running on to the present. */
    fun url(p: PlaylistEntity, ch: ChannelEntity, listing: CatchupListing, startMs: Long): String {
        if (p.isM3u) {
            val type = ch.catchupType
            val stream = ch.streamUrl
            return (if (type != null && stream != null) M3uCatchup.url(stream, type, ch.catchupSource, startMs, clock()) else null)
                ?: throw IOException(appString(R.string.data_catchup_unplayable))
        }
        // A minute over, so the stream doesn't end just short of now.
        val minutes = ((clock() - startMs) / 60_000).toInt().coerceAtLeast(1) + 1
        return playlists.client(p).catchupUrl(ch.streamId, startMs, listing, minutes)
    }

    companion object {
        const val DAY_MS = 24 * 3_600_000L
        private const val CACHE_CHANNELS = 16
        private const val MAX_AGE_MS = 10 * 60_000L
    }
}
