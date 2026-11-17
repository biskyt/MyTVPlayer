package com.biskyt.mytvplayer.core.vod

/** Someone in the cast ([role] is the part) or crew, with their photo where known. As MyPlexPlayer's CastMember. */
data class CastMember(val name: String, val role: String?, val photoUrl: String?)
