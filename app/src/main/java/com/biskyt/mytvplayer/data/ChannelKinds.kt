package com.biskyt.mytvplayer.data

/**
 * Which channels a list shows: TV, radio stations, or both (see RadioMode). Every channel list is filtered by it, as
 * one condition in its query (`radio IN (:kinds)`), so the Radio section is Live TV's screens with another filter.
 */
enum class ChannelKinds(val values: List<Boolean>) {
    ALL(listOf(false, true)),
    TV(listOf(false)),
    RADIO(listOf(true));

    companion object {
        /** Live TV's lists ([radio] false) or the Radio section's, with radio stations put where [mode] says. */
        fun of(radio: Boolean, mode: RadioMode?): ChannelKinds = when {
            radio -> RADIO
            mode == RadioMode.SECTION || mode == RadioMode.HIDE -> TV
            else -> ALL
        }

        /** Lists outside the sections (Home, Search, Catch-up): radio stations too, unless they are hidden. */
        fun shown(mode: RadioMode?): ChannelKinds = if (mode == RadioMode.HIDE) TV else ALL
    }
}
