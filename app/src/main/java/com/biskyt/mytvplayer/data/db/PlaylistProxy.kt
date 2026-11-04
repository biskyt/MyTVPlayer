package com.biskyt.mytvplayer.data.db

import com.biskyt.mytvplayer.core.net.ProxySettings
import com.biskyt.mytvplayer.core.net.ProxyType

// Outside PlaylistEntity: Room's code generator cannot read the core proxy types on an entity.

/** The playlist's proxy, when one is set up and complete. */
val PlaylistEntity.proxy: ProxySettings?
    get() {
        val type = ProxyType.entries.firstOrNull { it.name == proxyType } ?: return null
        return ProxySettings(type, proxyHost.orEmpty(), proxyPort ?: 0, proxyUsername.orEmpty(), proxyPassword.orEmpty())
            .takeIf { it.valid }
    }

/** This playlist with [proxy] (null: none). */
fun PlaylistEntity.withProxy(proxy: ProxySettings?) = copy(
    proxyType = proxy?.type?.name,
    proxyHost = proxy?.host?.trim(),
    proxyPort = proxy?.port,
    proxyUsername = proxy?.username?.ifEmpty { null },
    proxyPassword = proxy?.password?.ifEmpty { null },
)
