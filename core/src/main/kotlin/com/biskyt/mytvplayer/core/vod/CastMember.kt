package com.biskyt.mytvplayer.core.vod

/**
 * An actor and the part played ([role]), or someone of the crew, with their photo where known. [id] is the person's id
 * on TMDB: their page needs it. If [id] is null (the provider's names only), the page does not open. As MyPlexPlayer's
 * CastMember (where [id] is the person's id on the server).
 */
data class CastMember(val name: String, val role: String?, val photoUrl: String?, val id: Long? = null)
