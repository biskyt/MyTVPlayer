# Syncing watch progress between TVs

Status: parked (planned 1 November 2026). The roadmap links to this file. Start the work only when the owner asks.

## The need
- The owner has MyTVPlayer on several TVs.
- They want to stop a film or an episode on one TV and continue from the same point on another TV.
- The sync is for all users. It is opt-in and off by default.
- MyTVPlayer must stay lightweight: no new app libraries, and no background polling.

## The services that we compared
| | Google Drive (chosen) | OneDrive | Own server (Cloudflare Worker) | Trakt | Plex |
|---|---|---|---|---|---|
| Server for the owner to run | None | None | Yes | None | None |
| Owner's cost | Free | Free | Free tier, with daily limits | VIP, about $60 a year | Free |
| Sign-in on a TV | Device code | Device code | A pairing code | Device code | `plex.tv/link` code |
| Google or Microsoft review | Basic only (the scope is non-sensitive) | "Unverified publisher" text unless verified | None | None | None |
| Who holds the data | The user's own Drive | The user's own OneDrive | The owner | Trakt | Plex |
| Items that can sync | All, M3U too | All, M3U too | All, M3U too | Only items with a TMDB or IMDB id | Only items that match Plex's catalogue |
| Resume point | Exact position | Exact position | Exact position | A percentage | Not confirmed |

### Findings
- **Trakt:**
  - Since the middle of 2026, only VIP accounts can create a Trakt API app.
  - Since July 2026, a free Trakt account can connect only one third-party app.
  - Trakt matches items only by TMDB, IMDB or TVDB ids, and keeps progress as a percentage.
  - In December 2025, a Trakt API change removed the scrobble "pause" call. The "stop" call now needs a minimum progress.
- **Google Drive:**
  - Google's device sign-in flow (for TVs) allows only a few scopes. `drive.appdata` and `drive.file` are two of them.
  - Google classes `drive.appdata` as non-sensitive, so the app needs only basic verification.
  - `drive.appdata` gives a hidden folder that only the app can read. The user's other files stay private from the app.
  - The sign-in uses plain HTTPS, so it needs no Google Play services. It also works on Fire TV. The user signs in on a phone or computer.
- **OneDrive:** the `Files.ReadWrite.AppFolder` scope gives a similar app folder, with a device code sign-in. The consent screen shows "unverified publisher" unless the owner completes Microsoft's publisher verification.
- **Own server:** a Cloudflare Worker with D1 (Cloudflare's SQL database) is free up to about 100,000 requests a day. The owner then runs a service, holds the data and must update Play's Data safety form.
- **Plex:**
  - Plex keeps watch state only for items on a Plex server, or items in Plex's own online catalogue.
  - IPTV items must match the catalogue by TMDB id. M3U items cannot match.
  - The API for catalogue items is not documented. It has a "scrobble" (watched) action. We did not find a way to save a resume position.
  - This makes Plex a poor choice for TV-to-TV sync. It is a good choice for sending watched marks to MyPlexPlayer (phase 2).

## What the app has now (November 2026, version 1.8.18)
- **Progress table:** `watch_progress` (`WatchProgressEntity`, `app/.../data/db/Entities.kt`).
  - Key: `(playlistId, kind, itemId)`. Columns: `positionMs`, `durationMs`, `updatedAt`, `title`, `imageUrl`, `seriesId`, `season`, `episode`, `episodeTitle` and `hasNext`.
  - "Watched" is a full position (`VodRepository.markEpisodeWatched`). There is no flag.
  - A row at position 0 is an "up next" placeholder for Carry on watching.
- **Writes and reads:**
  - `VodPlayerViewModel` saves every 15 s, and on pause, Back, the end of an item and the next episode.
  - `VodRepository.saveProgress`, `forgetProgress` and `forgetEpisode` change the rows.
  - Home reads them with `observeCarryOn`.
- **Playlist ids are local.** The same playlist has a different `playlistId` on each TV.
  - The identity that is the same on all TVs: `kind` + `server` + `username` (Xtream), or `server` (the M3U URL).
  - Xtream item ids, and M3U ids made from names, are the same on two TVs that have the same playlist.
- **External ids:** the app stores no TMDB or IMDB ids. Most Xtream panels send `tmdb_id` (films) and `tmdb` (series), but the app does not parse them.
- **Code to reuse:**
  - `AppContainer.http` (OkHttp) and kotlinx.serialization.
  - WorkManager (`RefreshWorker` has a network constraint).
  - `QrCode(text)` in `ui/common/QrCode.kt`.
  - `okhttp-mockwebserver` for tests.
- **Room** is at version 8.
- Catch-up and live keep no resume point, so they are not in scope.

## Plan: phase 1, sync through Google Drive (1.9.0)
### Storage format
- Each TV writes only its own file, `progress-<deviceId>.json`, in the Drive app folder (`appDataFolder`). So two TVs never write the same file.
- The file holds this TV's rows and deletes.
  - Rows: `playlistKey|kind|itemId` → position, duration, `updatedAt` and the display fields.
  - The playlist key is a SHA-256 of the normalised playlist identity. So the file has no server address and no user name.
  - Deletes are tombstones with `updatedAt`. A tombstone is a record that says "this row was deleted". Tombstones are kept for 60 days.
  - The file is gzip-compressed. It is less than 30 KB for the 300-row limit.
  - The file has a version number. Readers ignore unknown fields, so TMDB ids can be added later.
- A TV reads the other TVs' files. It merges them with "last write wins" on `updatedAt`.

### `core` (pure Kotlin, OkHttp only)
- `sync/GoogleDeviceAuth.kt`: the device code request, polling at the given interval, and the token refresh.
- `sync/DriveStore.kt`, behind a small `SyncStore` interface (so OneDrive can be added later):
  - list the app-folder files changed since a time (one request);
  - download a file;
  - create or update this TV's file.
- `sync/ProgressMerge.kt`: rows and tombstones, last write wins, and the file format.

### App
- **Room 8→9 (AutoMigration):** add a `sync_tombstone` table for deletes ("remove from Carry on watching" and "forget"). The local trim to 300 rows makes no tombstones.
- **Push (`data/ProgressSync.kt`):**
  - It uploads this TV's file on pause, stop, Back, the end of an item and the next episode.
  - During playback, it uploads at most once every 2 minutes.
  - If an upload fails, a one-off WorkManager job with a network constraint uploads the file again.
- **Pull:**
  - The app pulls when it comes to the foreground, when Home opens, and when a film or series page opens.
  - Before a resume, the player waits a maximum of 2 seconds for a pull.
  - Rows for a playlist that this TV does not have are kept in the merge, but not shown.
- **Merge:** rows from other TVs go into `watch_progress`. A merged row is not uploaded again as this TV's change.
- **Settings › "Sync between TVs"** (a main settings row, off by default):
  - Subtitle: "Uses your Google Drive to keep your place in films and episodes the same on all your TVs."
  - Sign-in: the TV shows the code, the address `google.com/device` and a QR code. The user signs in with the same Google account on each TV.
  - The screen also shows "Last synced", the account's email address, and "Stop syncing". "Stop syncing" deletes this TV's file and the tokens.
  - The tokens are in their own DataStore key, not in `Settings`, so backups do not include them.
- **Text:** new strings in en, es, pt-BR and fr.

### Owner's one-time set-up (before the release)
1. Create a Google Cloud project with a neutral name, and enable the Drive API.
   - Use one project for both MyTVPlayer and MyPlexPlayer. Drive gives the app folder to the project, so both apps can then use the same files.
2. Set up the OAuth consent screen: the app name, `docs/privacy.html` and the home page. `drive.appdata` needs only basic verification.
3. Create an OAuth client of type "TVs and Limited Input devices".
4. Add the client id and secret as GitHub secrets for the release build.
   - Google does not treat a device client's secret as secret.
   - Debug builds read the client id and secret from `local.properties`.
5. Update Play's Data safety form and `docs/privacy.html`: progress goes to the user's own Google Drive.

### Effort and overhead
- **Effort:** about the size of the catch-up work, about 15 files, in one PR.
- **On the TV:** no new libraries and no polling. There are a few small requests at playback events and when the app opens.
- **For the owner:** no server and no cost.

### Verification
- **core:** tests against MockWebServer:
  - the device flow (pending, slow down, denied, expired, refresh);
  - the Drive list, download and upload;
  - the merge rules, tombstones and old file versions.
- **App (Robolectric):**
  - Two databases with the same playlist under different local ids, and one fake Drive.
  - Progress saved on "TV A" shows in Carry on watching and as the resume point on "TV B".
  - A delete on B removes the row on A.
  - A failed upload is sent again.
  - With sync off, the app makes no requests.
- **Settings UI test:** the sign-in code and QR show, sign-in completes, and "Stop syncing" works.

## Plan: phase 2, watched marks to Plex (low priority)
- MyTVPlayer signs in to Plex with the `plex.tv/link` code. This uses the same code as MyPlexPlayer's sign-in.
- The Xtream client parses the TMDB ids, and the app stores them.
- For each film or episode that matches Plex's catalogue by TMDB id, MyTVPlayer sends the watched mark to the user's Plex account.
- The marks then show in MyPlexPlayer and in every other Plex app. MyPlexPlayer needs no change.
- Only watched marks sync. A resume position cannot, because the two copies of a film can have different lengths or cuts.
- M3U items have no ids, so they cannot match.

## Sources (checked November 2026)
- [Google: OAuth 2.0 for TV and limited-input devices (allowed scopes)](https://developers.google.com/identity/protocols/oauth2/limited-input-device)
- [Google: choose Google Drive API scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth)
- [Microsoft: OneDrive app folder](https://learn.microsoft.com/en-us/graph/onedrive-sharepoint-appfolder)
- [Trakt API apps became a VIP feature (Traktivity plugin notes)](https://wordpress.org/plugins/traktivity/)
- [Trakt limits free users to one connected app (July 2026)](https://troypoint.com/trakt-limits-users-to-one-connection/)
- [Trakt free-tier limits and VIP price (February 2025)](https://alternativeto.net/news/2025/2/trakt-tv-has-set-stricter-limits-for-free-users-and-raised-vip-subscription-prices-by-100-/)
- [Trakt playback API change (Infuse forum, December 2025)](https://community.firecore.com/t/playback-sync-issues/58251)
- [Cloudflare: D1 free-tier limit enforcement](https://developers.cloudflare.com/changelog/post/2026-09-01-d1-free-tier-limit-enforcement/)
- [Plex: setting viewOffset through the API (forum, 2018)](https://forums.plex.tv/t/api-set-viewoffset-cli-client-prototype/287507.md)
