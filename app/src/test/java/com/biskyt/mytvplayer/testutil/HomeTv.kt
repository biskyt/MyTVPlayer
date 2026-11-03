package com.biskyt.mytvplayer.testutil

import com.biskyt.mytvplayer.AppContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * Adds [provider] as the "Home TV" playlist and loads its channels, the start of most UI tests; with [vod] its films
 * and series too, and with [guide] its guide. Returns the playlist's id.
 */
suspend fun AppContainer.addHomeTv(provider: FakeProvider, vod: Boolean = false, guide: Boolean = false): Long {
    // As on a TV after its first start: the parental controls row is there, so writing it won't reload the lists later.
    withTimeout(5_000) { while (db.parental().get() == null) delay(10) }
    val id = playlists.add("Home TV", provider.url, "me", "secret")
    playlists.syncChannels(id)
    if (vod) this.vod.sync(playlists.get(id)!!)
    if (guide) {
        playlists.get(id)?.let { epg.ensureProviderSource(it, playlists.client(it).xmltvUrl()) }
        epg.importAll(listOf(id))
    }
    return id
}
