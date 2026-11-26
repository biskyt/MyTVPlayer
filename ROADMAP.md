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

## Next release (the owner, 2026-11-26)
- **The player's More menu keeps the focus on its option.** When options load above it, the focus moves with the
  option, as in the other options panels since 1.41.3 (`ItemActionsPanel` in `ui/common/ItemActions.kt`). The More
  menu's rows are in a lazy list, so the rows that are not on screen have no focus requester yet.
- **Options panels keep the focus when a row changes its kind.** `FollowedOption.focus` skips its request when the
  option followed is still at the row `at`. But when an option used loads late into the row of its stand-in, and the
  two are of different kinds (an option and a row of links), the focused row is replaced, and nothing has the focus.
  Skip the request only while that row still has the focus (MyPlexPlayer's review of 1.41.3).
- **Focus inside a row of links.** A link that is not the first does not update the option followed, so an option that
  loads above takes the focus back to the row's first link (MyPlexPlayer's review of 1.41.3).
- **The Google TV 16 smoke test on the emulator.** `SmokeTest` uses the plain `createAndroidComposeRule`, whose effects
  run on an unconfined dispatcher. Use MyPlexPlayer's fix (its PR 140): move `ComposeRules.kt` to `app/src/sharedTest`,
  use `createUiComposeRule<MainActivity>()` in `SmokeTest`, and stop the main thread's effects after the activity closes.
- **`HideCategoryUiTest`, "hiding the last category chooses the one before it".** It failed one time in a full local
  run: the focus went to the channel list. Find the root cause (perhaps `RefocusEffect` in `CategoryColumn` and the
  panel window that closes).

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

## Sync watch progress between TVs
- **Phase 1 (1.10.0):** keep your place in films and episodes the same on all your TVs, through the user's own Google Drive. It is off by default.
- **Phase 2 (low priority, planned):** send watched marks to the user's Plex account, so that they show in MyPlexPlayer.
- The plan, the services that we compared and the sources are in [`research/progress-sync.md`](research/progress-sync.md).

## Later
- Recording to USB storage (DVR).
- More touch: portrait phone layouts, foldables and player gestures (1.0 has touch in landscape).

## Not planned
PIN lock, multi-view, Stalker portals, Trakt, voice search, picture-in-picture.
