# MyTVPlayer roadmap

## Done
- **0.1–0.2:** Live TV (list and TV guide), player, guide matching, playlists (Xtream and M3U), free iptv-org channels, settings.
- **0.3–0.7:**
  - Movies and Series, with resume and "Carry on watching".
  - Global search.
  - Backup and restore.
  - Better guide matching.
  - "Find in Series / Movies" from the TV guide.
- **0.8:** Catch-up TV: per-channel catch-up page, Start over, and past programmes in the TV guide (Xtream).
- **0.9:** Movies and series from M3U playlists, M3U catch-up, and provider M3U links added as logins.
- **1.0:** Touch support for phones and tablets, and the free channels' own programme guide.
- **1.1:** The app in UK and US English, Spanish, Portuguese (Brazil) and French. Also a category filter, and a new
  search order.

## Milestone 2: Movies, Series and search
- Programme reminders.
- Optional TMDB artwork and details for movies and series (can be turned off).
- OpenSubtitles (off by default).

### "Find in Series / Movies" from the TV guide (done in 0.7.0)
In the TV guide, a programme's options menu (hold OK, or Info) offers a way to watch it on demand.

- **What the menu shows:**
  - **Series** (an episode number, or a series-type category): **Find series** opens the series, or the exact episode when it can be matched.
  - **Film:** **Find in Movies**.
  - **Clearly neither** (news, sport, live events): no option at all.
  - **Unknown:** **Search for "<title>"**, which opens search pre-filled with the title.
- **How it works:**
  - Take two things from the guide data the importer already reads: the XMLTV `<category>` and `<episode-num>` (`xmltv_ns` "S.E.P", or onscreen "S01E02").
  - Store them as a tiny `kind` (series / movie / other / unknown) plus season and episode numbers on each programme.
  - No extra downloads or lookups.
  - Search by normalised title (reusing the channel-name normaliser). It opens the series, then the episode if the season and episode numbers match.
  - If any of this turns out to be costly, fall back to the pre-filled search alone.

## Planned: sync watch progress between TVs (parked)
- **Phase 1:** keep your place in films and episodes the same on all your TVs, through the user's own Google Drive. It is off by default.
- **Phase 2 (low priority):** send watched marks to the user's Plex account, so that they show in MyPlexPlayer.
- The plan, the services that we compared and the sources are in [`research/progress-sync.md`](research/progress-sync.md).

## Later
- Recording to USB storage (DVR).
- More touch: portrait phone layouts, foldables and player gestures (1.0 has touch in landscape).

## Not planned
PIN lock, multi-view, Stalker portals, Trakt, voice search, picture-in-picture.
