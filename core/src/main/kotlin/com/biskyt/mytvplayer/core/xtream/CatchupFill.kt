package com.biskyt.mytvplayer.core.xtream

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * A catch-up channel's whole timeline, with nothing missing (the owner, 2026-11-22): a provider's catch-up listing can
 * leave out times, or a whole day, though the channel's archive plays them (as other apps show). The gaps are filled
 * with the TV guide's programmes, and what the guide does not cover with slots of an hour at most, cut at the hours,
 * that replay from their start.
 */
object CatchupFill {
    /** A gap shorter than this (programmes that do not quite meet) is not given a slot of its own. */
    private const val MIN_GAP_MS = 5 * 60_000L

    /**
     * [provider]'s programmes as they are, then [guide]'s where none of them is, then slots for the rest, from the start
     * of [window] (the oldest catch-up) to the end of the hour its end (now) is in (so that something is on now, for
     * Start over). [slotTitle] names a slot by
     * its start ("Replay from 14:00"). Everything filled in can be replayed. In start order.
     */
    fun fill(
        provider: List<CatchupProgramme>,
        guide: List<CatchupProgramme>,
        window: LongRange,
        zone: ZoneId,
        slotTitle: (Long) -> String,
    ): List<CatchupProgramme> {
        val known = provider + guide.filter { g -> provider.none { it.overlaps(g.startMs, g.stopMs) } }
            .map { it.copy(hasArchive = true) }
        val fromMs = window.first
        val end = nextHour(window.last, zone)
        val slots = ArrayList<CatchupProgramme>()
        var at = fromMs
        // The gaps between what is known, from the start of catch-up to the end of the hour.
        for (p in known.filter { it.stopMs > fromMs && it.startMs < end }.sortedBy { it.startMs }) {
            if (p.startMs > at) slots += slotsFor(at, p.startMs, zone, slotTitle)
            at = maxOf(at, p.stopMs)
        }
        if (end > at) slots += slotsFor(at, end, zone, slotTitle)
        return (known + slots).sortedBy { it.startMs }
    }

    /**
     * [from] to [to] in slots cut at the hours; none for a short gap. A piece shorter than [MIN_GAP_MS] at either end
     * joins the slot next to it, so a gap from 13:58 starts with a slot "from 13:58" to 15:00, not one of two minutes.
     */
    private fun slotsFor(from: Long, to: Long, zone: ZoneId, title: (Long) -> String): List<CatchupProgramme> {
        if (to - from < MIN_GAP_MS) return emptyList()
        val cuts = generateSequence(nextHour(from, zone)) { nextHour(it, zone) }.takeWhile { it < to }
            .filter { it - from >= MIN_GAP_MS && to - it >= MIN_GAP_MS }.toList()
        val starts = listOf(from) + cuts
        val stops = cuts + to
        return starts.zip(stops) { start, stop -> CatchupProgramme(start, stop, title(start), null, hasArchive = true) }
    }

    /** The start of the hour after the one [ms] falls in, in [zone]'s hours. */
    private fun nextHour(ms: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(ms).atZone(zone).truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()

    private fun CatchupProgramme.overlaps(start: Long, stop: Long) = startMs < stop && stopMs > start
}
