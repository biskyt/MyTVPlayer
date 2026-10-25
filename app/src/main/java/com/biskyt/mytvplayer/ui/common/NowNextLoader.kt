package com.biskyt.mytvplayer.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.LifecycleStartEffect
import com.biskyt.mytvplayer.AppContainer
import com.biskyt.mytvplayer.data.db.ChannelProgramme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Loads per-channel values only for rows that are on screen. Rows call [request] when shown; requests are collected
 * for a moment and fetched in one query. Results live in a snapshot map so only the affected row redraws.
 * [scope] must run on the main thread.
 */
open class BatchLoader<V : Any>(
    private val scope: CoroutineScope,
    private val load: suspend (ids: List<Long>) -> Map<Long, V>
) {
    val entries = mutableStateMapOf<Long, V>()

    /** Recently shown rows, oldest first; capped so scrolling through thousands of channels doesn't fill memory. */
    private val requested = object : LinkedHashMap<Long, Unit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Unit>?): Boolean {
            val evict = size > MAX_ROWS
            if (evict && eldest != null) this@BatchLoader.entries.remove(eldest.key)
            return evict
        }
    }
    private val pending = LinkedHashSet<Long>()
    private var batchJob: Job? = null

    fun request(id: Long) {
        if (requested.put(id, Unit) != null) return // already loaded or loading; put() also marks it recently used
        pending += id
        if (batchJob != null) return
        batchJob = scope.launch {
            delay(BATCH_DELAY_MS)
            val ids = pending.toList()
            pending.clear()
            batchJob = null // requests arriving during the fetch start a new batch
            fetch(ids)
        }
    }

    /** Reloads every row loaded so far (e.g. the guide changed), keeping old values on screen until new ones arrive. */
    fun refreshAll() {
        val ids = requested.keys.toList()
        if (ids.isNotEmpty()) scope.launch { ids.chunked(REFRESH_CHUNK).forEach { fetch(it) } }
    }

    /** Reloads one row, e.g. after its guide channel was changed. */
    fun refresh(id: Long) {
        requested[id] = Unit
        scope.launch { fetch(listOf(id)) }
    }

    protected suspend fun fetch(ids: List<Long>) {
        val result = load(ids)
        ids.forEach { id -> result[id]?.let { entries[id] = it } }
    }

    private companion object {
        const val BATCH_DELAY_MS = 120L
        const val REFRESH_CHUNK = 500
        const val MAX_ROWS = 300
    }
}

@Immutable
data class NowNext(val now: ChannelProgramme?, val next: ChannelProgramme?) {
    fun progress(nowMs: Long): Float = now?.let { (nowMs - it.startMs).toFloat() / (it.stopMs - it.startMs).coerceAtLeast(1) } ?: 0f

    /** True once the shown programme has ended or a gap before the next one has passed. */
    fun isStale(nowMs: Long) = (now != null && now.stopMs <= nowMs) || (now == null && next != null && next.startMs <= nowMs)
}

/** Now/next from the guide for channels of the playlist [playlistId] gives (titles only), for [scope]'s screen. */
fun guideNowNext(scope: CoroutineScope, c: AppContainer, playlistId: () -> Long) =
    NowNextLoader(scope, c.epg.changes) { ids, from, to -> c.epg.programmes(playlistId(), ids, from, to, details = false) }

/**
 * Now/next for visible channels. Every minute, rows whose programme has ended are refreshed, and everything is
 * reloaded when [guideChanges] emits (a guide import finished or a channel's guide was changed).
 */
class NowNextLoader(
    private val scope: CoroutineScope,
    guideChanges: Flow<Any>? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Loads programmes overlapping from..to; titles are enough here, so callers can skip descriptions. */
    load: suspend (ids: List<Long>, from: Long, to: Long) -> Map<Long, List<ChannelProgramme>>
) : BatchLoader<NowNext>(scope, { ids ->
    val now = clock()
    val result = load(ids, now, now + LOOKAHEAD_MS)
    ids.associateWith { id -> pick(result[id].orEmpty(), now) }
}) {
    /** False while the screen is in the back stack (e.g. Home under the player): nothing is reloaded then. */
    private var visible = true
    private var missedChange = false

    init {
        scope.launch {
            while (isActive) {
                delay(60_000 - clock() % 60_000)
                if (visible) refreshStale()
            }
        }
        guideChanges?.let { flow ->
            scope.launch { flow.drop(1).collect { if (visible) refreshAll() else missedChange = true } }
        }
    }

    /**
     * Call from the screen showing these rows. While it's covered by another screen (its view model kept in the back
     * stack) or the app is in the background, the minute updates and guide reloads pause, leaving the CPU to what is
     * on screen; on return, rows catch up.
     */
    @Composable
    fun PauseWhenHidden() {
        LifecycleStartEffect(this) {
            shown()
            onStopOrDispose { hidden() }
        }
    }

    internal fun hidden() {
        visible = false
    }

    internal fun shown() {
        if (visible) return
        visible = true
        if (missedChange) {
            missedChange = false
            refreshAll()
        } else {
            scope.launch { refreshStale() }
        }
    }

    private suspend fun refreshStale() {
        val t = clock()
        val stale = entries.filterValues { it.isStale(t) }.keys.toList()
        if (stale.isNotEmpty()) fetch(stale)
    }

    private companion object {
        const val LOOKAHEAD_MS = 8 * 3_600_000L

        fun pick(list: List<ChannelProgramme>, now: Long): NowNext {
            val current = list.firstOrNull { it.isLive(now) }
            val next = list.firstOrNull { it.startMs >= (current?.stopMs ?: now) && it !== current }
            return NowNext(current, next)
        }
    }
}

/** The current time, updated on each minute boundary: progress bars and "on now" labels advance without per-frame work. */
@Composable
fun rememberMinuteClock(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(60_000 - System.currentTimeMillis() % 60_000)
        value = System.currentTimeMillis()
    }
}
