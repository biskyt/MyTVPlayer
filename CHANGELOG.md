# Changelog

Each version's section becomes its GitHub release notes, so it is written for the people using the app.

## 1.7.0
- **The player's controls show the time of day** in the top corner, and for a film or episode how far in you are, how
  long it is and how long is left ("12:34 / 45:00" and "32:26 left"); on live TV, how long the programme has left.
- **The Options button on the remote brings up the controls with More picked out**, so OK opens it (and you can still
  move to the other buttons).
- **The controls are tidier and sit lower:** the hints about the remote's buttons are gone, and so are From the start
  and Next episode on films and episodes (the next episode still starts by itself at the end).
- Fixed: going on to the next episode could, very rarely, save it as watched.

## 1.6.0
- **Carry on watching shows posters**, like the other rows on Home, with what's left in short: "S1E2 22m" for an
  episode, "1h 5m" for a film.
- **Only what you've watched for 30 seconds goes on Carry on watching**, so a quick look at the start of something
  doesn't add it (anything already started, or played to the end, still counts at once).
- **The player's controls are lighter** on the picture, and the progress bar of a film, episode or catch-up programme
  shows a handle when it's selected (Up from the buttons), where left and right skip.

## 1.5.0
- **A TV guide that's easy to get around:** the highlight now moves one channel or one programme per press, held
  buttons included, however long the channel list or full the guide. Right goes on through the whole guide (no more
  stopping after a few hours), Left comes back along the same channel, and the highlight stays in the guide at its
  start and end. Up from the first channel or Back takes you to the filter, and from there to the categories.
- **Which day you're looking at:** the guide's time bar names the day (Today, Tomorrow or the date), with a line
  where a new day starts, and the programme details above the guide say the day too when it isn't today.
- The **List** and **TV guide** buttons keep the highlight on themselves, so you can switch back and forth.
- **All channels** shows how many channels there are, as the other categories do.

## 1.4.1
- **The TV guide keeps the highlight in it:** opening the guide with its button no longer sends the highlight to the
  Home tab on some TVs, and Right from the categories goes back to the programme you left, rather than to the buttons
  above the guide.

## 1.4.0
- TV guides are now chosen per playlist: switch a guide on or off for the playlist you're using, under TV guide
  sources. Each playlist's channels are matched only to its own guides, so another provider's guide no longer shows
  on its channels. A guide used by more than one playlist is still downloaded and stored once.
- Guide updates download only the guides of the playlist you're using. Switching to another playlist updates its
  guides there and then if they're out of date.

## 1.3.4
- Choosing categories: switching between live channels, films and series now always shows the first language's
  categories (it could show none).

## 1.3.3
- A film, episode or catch-up programme that's slow to start no longer closes the player (or shows an error) as it
  opens.

## 1.3.2
- Holding OK, Guide or Menu to open a menu or the guide no longer carries on into it: holding Guide or Menu while
  watching no longer also reaches the TV (which could open its own guide over the channel).

## 1.3.1
- While watching live TV with the channel guide open, the remote's Menu button now opens the menu in the guide's
  place (it did nothing before).

## 1.3.0
- **Mark an episode watched or unwatched:** on a series' page, hold OK on an episode (or press the remote's Menu
  button) and choose **Mark as watched**, or **Mark as unwatched** to start one again. Marking an earlier episode
  keeps your place in the series.

## 1.2.8
- Fixes to the dimmed paused screen: it dims again after the picture is lost and comes back, an error isn't left
  dimmed, and a button never needs pressing twice.

## 1.2.7
- **Paused, the controls stay on screen.** Left paused for two minutes, the screen dims, to spare OLED screens from
  burn-in. Any button lights it again, and the remote's play button plays straight away.

## 1.2.6
- **Clearer text:** secondary text across the app (channel names and programme descriptions while watching, times in
  the TV guide, what's on next, counts and hints) is much brighter. While watching, the shading behind the controls is
  darker, so they read over a bright picture too.

## 1.2.5
- **Dolby Vision films that also carry HDR10+ now play in Dolby Vision** on TVs that showed them as HDR10. On a
  screen without Dolby Vision, they still play in HDR10+.
- Hiding a category from the column now always leaves the remote on the category shown next.

## 1.2.4
- **Menu closes the menu:** while watching, pressing the remote's Menu button again closes the menu it opened, as
  Back does.

## 1.2.3
- **Long channel names show in full:** they take two lines in the TV guide, the channel list and the channel cards
  on Home and in Search, instead of being cut short. The guide's channel column is a little wider for them, with
  slightly smaller names.

## 1.2.2
- **Read what a programme is about:** while watching, the controls show the episode and the start of its description,
  and **About this programme** shows it all. In the TV guide, the description shows above the grid (on two lines),
  and in full when you hold OK on a programme.
- **Channels found in Search show what's on**, until when and what's next, on the same cards as Home.
- Hiding a category (hold OK on it) keeps your place: the category after it is chosen, or the one before for the
  last, instead of going back to the top.

## 1.2.1
- **Live TV (and the TV guide), Movies and Series open on your favourites** each time the app starts (or on
  everything, until you have some). The list you pick is kept while the app is open, and forgotten when it's closed.
- Channels are put in order only in Favourites: Move up and Move down are gone from the other lists. An order set with
  them before stays as it is.

## 1.2.0
- **Put your favourites in the order you like:** hold OK on a favourite (on Home, in Live TV's Favourites, or in
  Movies' and Series' Favourites), choose Move, move it with the arrows and press OK. Back leaves the order as it was.
  New favourites go at the end.
- **Home has rows for your favourite series and movies,** below Recently watched. Hold OK on one to open it or
  take it off your favourites.
- **Updates show in the corner of the screen:** while channels, the guide or movies and series update, a small box in
  the bottom-right corner says what's happening, with a progress bar where it can tell how far along it is. It shows on
  every screen except while watching, and a failed update shows in red for a few seconds. The menu bar no longer
  gets crowded by it.
- **Favourites are marked with a heart** on their logo or poster wherever they're listed: the TV guide, the channel
  list, Movies, Series and Search.
- Series now come before movies: in the menu bar, in Search and in Settings › Languages & categories.
- Settings shows the app's version on the About row.

## 1.1.1
- **OK pauses and brings up the controls** while watching (OK on Play carries on); up, down or Info bring them up
  without pausing. With "Change channel with up and down" on (Settings › Playback), OK only brings them up, as
  before.
- **Providers with more than 999 categories** couldn't be updated on Android 9, 10 and 11 (many TVs): the update
  failed every time.
- **Unticking every movie or series category now turns them off.** The next update used to tick the usual ones again.
- A TV guide source switched off (Settings › TV guide sources) is no longer used: its programmes go, its channels
  aren't offered to choose from, and at the next update channels are matched to your other guides. Switched back on,
  it returns with the next guide update, guide channels you chose by hand included.
- Pressing Home while a catch-up programme, a film or an episode was still starting could leave it playing in the
  background. Catch-up left that way then failed to start again on coming back.
- Settings shown as ◀ value ▶ now change with left and right (OK still moves on to the next value).
- The channel guide over live TV kept losing its place when moving between channels: after one whose programmes
  start at other times, or one with no guide, left and right stopped working. Quick presses could also skip a channel,
  and OK straight after them could watch the one before.
- The TV guide, left open past the half hour, started asking your provider for past programmes it hadn't asked for.
- A series whose provider lists the same season twice opened with an error.
- With M3U playlists, a movie or series category ticked while they were loading never loaded.
- Typing a number in Favourites and Recent now finds channels as the other lists do (a "0" listed every channel
  without a number), and searching guide channels takes "%" and "_" as they are.
- TV guides with times written "+01:00" or without seconds are now read correctly (they were an hour out, or
  missing). A programme whose end is before its start now shows for half an hour.
- Channel names such as "HBO-2" are matched to the guide by their whole name (they lost the "HBO").
- Catch-up from providers that send plain text: one-word titles such as "Kids" were shown garbled.

## 1.1.0
- **MyTVPlayer now speaks your language:** English (UK and US), Spanish, Portuguese (Brazil) and French. It starts
  in your TV's language, or in UK English if it isn't one of these. To choose another, go to Settings › Language.
- Times, dates, numbers and language names (in the audio and subtitle menus) follow the app's language.
- "1 of 1 category", "1 channel" and "the last 1 day" now read correctly.
- The app's own words for things a playlist or guide leaves unnamed ("No title"), and TV guide errors, now follow the
  app's language too. Names from your playlists and guides, and the names you give them, never change.
- M3U channels, films and series with no category of their own are now under "No category", apart from a category
  the playlist itself calls "Other" (they were together). Both keep whether you chose to show them.
- **A filter above the categories** in Live TV, Catch-up, Movies and Series: type part of a name to find a category
  in a long list.
- Search shows channels, then movies and series, then TV programmes.

## 1.0.1
- **Restoring a backup on TVs without Android's file picker**: instead of "no application can handle this request",
  MyTVPlayer now offers the file manager apps installed (such as X-plore). You can also open the backup file from a
  file manager with Open with or Share and choose MyTVPlayer: it goes to Backup & restore, which asks before
  restoring.
- A channel's catch-up now opens with focus on its programmes (the first key press went to the top bar). Right from
  the days goes to the programmes, and back from watching one, focus is on it again.
- With the Audio, Subtitles or More panel open over live TV, the Guide button now opens the channel guide, as it does
  with no panel open (it opened the TV's own guide).
- In the TV guide, moving away (up to the filter, or to the categories) before a channel's programmes have loaded no
  longer has focus jump back into the guide when they arrive.
- Settings › Playlists: Update now shows its progress, then whether the playlist updated or what went wrong, as the TV
  guide page does.

## 1.0.0
The first full release: phones and tablets now work by touch, and the free TV has its own programme guide.

- **Fix: setting up or updating an M3U playlist (including the free TV) crashed the app** on Android devices since
  0.9.0. A pattern for reading catch-up addresses that Java accepts is rejected by Android, and the tests only ran on
  Java. A new test now checks every pattern the way Android does.
- **Films and posters from Wikimedia and archive.org:** MyTVPlayer now tells servers who it is (its name, version and
  web address), as Wikimedia asks of apps; without it, Wikimedia refused films ("429") and pictures could fail to load.
- **No more endless "Loading…":** a stream that can't get started because its server sends it too slowly (a little
  at a time, or only some of it) is now treated like one that fails: tried again a few times, then an error with
  Retry, instead of loading for ever.
- On phones and tablets the app now fills the screen (swipe from the edge to see the status bar), so the top bar is
  no longer under the status bar, where taps didn't reach it.
- Free TV now comes with its own TV guide, so setting it up no longer adds a free guide for each country you pick.
  (Country guides can still be added in Settings › TV guide.)
- **Touch screens:** MyTVPlayer now works on phones and tablets (in landscape) as well as with a remote. Tap anything
  to choose it, and press and hold for its options (as holding OK does). On the player, tap the picture to show or
  hide the controls, tap or drag along the play bar to skip in films, series and catch-up, and tap the back arrow at
  the top left to return to the list. The TV guide has Earlier and Later buttons, and the channel guide beside the
  picture has Previous and Next. Tap beside a side panel to close it. Nothing changes on a TV.
- Dim text (times, counts, hints) is a little lighter, so it's easier to read on every background.
- **If MyTVPlayer closes unexpectedly**, the next start says so and shows what went wrong, with a code to scan on your
  phone that opens a GitHub issue with the details (logins are left out; nothing is sent unless you send it). "Continue
  without updating" starts without refreshing your playlist, in case that's what went wrong.
- Settings › Languages & categories: the languages and the categories each have their own Select all / none, so you
  can choose every category of one language without choosing every language.

## 0.9.5
- **Live TV that won't start in one format is tried in the other.** If a channel from your provider doesn't start in
  MPEG-TS, HLS is tried (or the other way round, if you chose HLS). When the other one plays, your provider's channels
  start in it from then on. Choosing a format again in Settings › Playback starts afresh.
- **Select all or none** in Settings › Languages & categories, for live TV, movies or series. While filtering, they
  apply to the matching categories only.
- **Fix: setting up free TV loaded nothing.** Its categories name genres but no country, so none matched your TV's
  country and none were chosen. A playlist whose categories name no country or language now has them all chosen to
  start with.
- **Fix: "Restore from a file" on TVs without Android's file picker.** Some TVs answer with "No application can handle
  this request" instead of a picker. MyTVPlayer now spots this and offers your file manager app (e.g. X-plore).

## 0.9.4
- **New free TV, legal to watch:** the free option on the first screen (and in Settings › Playlists) now sets up
  [biskyt/legalFreeIPTVplaylist](https://github.com/biskyt/legalFreeIPTVplaylist): live channels streamed free by
  their broadcasters and free ad-supported services, plus public-domain and openly licensed films and series. It
  replaces iptv-org's list, which also includes unofficial streams. A free iptv-org playlist you already have keeps
  working.
- M3U movie and series playlists can say what each title is (`tvg-type`, `series-name`, `season`, `episode` and
  `year`, an open convention); the app follows them instead of guessing from the names.
- M3U playlists that give each channel, film or series its country or language (`tvg-country`, `tvg-language`):
  categories named only by genre ("News", "Movies: Drama") are split by country, so Settings › Languages & categories
  lists them under their country or language, your TV's own is chosen to start with, and the free TV guide for each
  country you pick is added. Where two countries' categories share a name, the list shows which is which
  ("News · Swedish"). Categories whose names already say their country ("UK | Sports") stay as they are.

## 0.9.2
- A lighter, slightly smaller look: text is drawn in Nunito Bold and Medium (it was ExtraBold and SemiBold) at 92% of
  its old size.
- When the player's controls come up (as playback starts, or on OK, ▲ or ▼), Play/Pause has focus. ▲ goes back to the
  video, where ◀ ▶ skip (and scrub, held). With "Up/down change channel" on, ▲ ▼ change channel with the controls up too.
- The remote's play/pause, rewind, fast forward and stop buttons now work with the channel guide or the Audio,
  Subtitles and More panels open (rewind, fast forward and stop close the panel).
- Fixed: the remote's Guide button with focus on the Live TV or Catch-up tabs opened the TV's own guide; it now switches
  between the list and the TV guide, as it does elsewhere on the screen.
- Fixed: in the TV guide (and the Catch-up tab's timeline), ▲ from the first row could stay stuck there instead of going
  up to the filter box.

## 0.9.1
- Settings › About now ends with the Ko-fi and GitHub Sponsors links too, and both buttons have icons.
- New **Open source licences** page (Settings › About): every library and the font the app is built with, with its
  version, licence and what it's used for. Select one for a QR code to its project page.

## 0.9.0
- **Movies and series from M3U playlists**: when adding or editing an M3U playlist, give its movies playlist and/or
  series playlist (both optional). One playlist with both works: episodes named like "Show S01E02" go to Series,
  grouped by show and season, and the rest to Movies. They work like a provider's: categories, favourites, resume and
  "Carry on watching". M3U playlists don't say more than a title and picture, so there's no plot or cast.
- **Catch-up on M3U channels** that declare it in their playlist (`catchup`, `catchup-days`, `catchup-source`): the
  Catch-up tab, the ⟲ mark and the TV guide's past work as for Xtream channels, with the past in hour-long slots.
  "Start over" goes back to the start of the programme on now, if the TV guide has it.
- Adding a provider's M3U link (`…/get.php?username=…&password=…`) adds it with its login instead, so its movies,
  series and catch-up come too. Links that ask for another stream format or a selection of channels stay playlists.
- Fixed: subtitles never appeared on screen. Text and picture (DVB) subtitles now show over the video.
- Picture options do what they say: Fit, Zoom to fill (crops to fill the screen), Stretch, and now 16:9 and 4:3 for
  channels whose picture is the wrong shape.
- Choosing an audio or subtitle track now says what's playing a moment later (or that the channel wouldn't switch).
- Fixed: an audio or subtitle choice could carry over to the next channel, or stop applying after a reconnect.

## 0.8.9
- Settings now ends with **Support MyTVPlayer**: Ko-fi and GitHub Sponsors. Each shows a QR code to scan with your
  phone (and can open the page on the TV, if it has a browser).

## 0.8.8
- Runs on Android 9 and 10 as well as 11+, so older Fire TVs on Fire OS 7 (Fire TV Stick Lite, HD and 3rd gen, Cube
  2nd gen, many Fire TV televisions) and older Android TV boxes can install it.
- On Android 9, Settings › Backup & restore asks for permission to use the Download folder, so it can save the backup
  there and find one already there (it's never replaced without asking).

## 0.8.7
- While watching, up and down show the controls; the remote's channel buttons change channel. To change channel with
  up and down as before, turn on Settings › Playback › **Change channel with up and down**.
- Scrub through movies, episodes and catch-up: hold ◀ or ▶ and a marker runs along the play bar, faster the longer
  you hold; playback jumps there when you let go. A single press still skips.
- The Catch-up tab lists its channels by category (e.g. UK and US separately), with "All catch-up" at the top.
- Channels whose provider marks them as having catch-up without saying for how long now show as catch-up channels
  (taken as a week).
- Back in the channel list or TV guide goes up to the filter box; Back again leaves.
- Settings › Appearance: the custom colour bar is gone (it didn't work); pick one of the preset colours.

## 0.8.6
- **Catch-up** has its own tab (in place of Favourites, which Home and each section already show), for playlists
  with catch-up channels. It lists those channels; OK opens a channel's past programmes. Live TV also has a Catch-up
  list.
- Channels with catch-up have a small ⟲ clock mark in the channel list, the TV guide and on Home.
- Hold OK (or press Menu) on a category in Live TV, Movies or Series to hide it. Show it again in Settings ›
  Languages & categories.
- Hold OK on a Home card for its options: watch, favourites, catch-up, change guide source, or take it off "Recently
  watched" or "Carry on watching".
- Fixed: the TV guide sometimes jumped to another category, when switching from the channel list or when scrolling
  back through catch-up. A category now only changes when you move through the categories yourself, and moving
  left into them lands on the one you're in.

## 0.8.5
- Adding a TV guide is much quicker: only the new guide's programmes are loaded (and those of channels whose guide
  changed), not every guide again. "Downloading" and "Loading programmes" now show how far they've got.
- The daily guide update skips guides that haven't changed on their server.
- Search results are easier to read: channel names have more room, and long names scroll while highlighted (movie
  and series titles too). Hold OK, or press the Menu button, on a result for its options: add to favourites,
  catch-up, change guide source, and more.
- The player's More menu has actions for what's playing: add or remove a channel from favourites, catch-up and change
  guide source on live TV; add to favourites and open the details or all episodes for movies and series.
- Fixed: the player's More menu always said "No audio", even while sound was playing.

## 0.8.4
- When a channel, film or episode won't play, the message now says why: the stream's server refused it (often because
  it only plays in its own country), the stream has gone from its address, the server didn't answer, or this TV box
  can't decode or read what it sent. It also names the server the stream comes from.
- Settings › Playlists shows when your provider's subscription expires, in amber when it's two weeks away or has
  passed. This now works for providers added with an M3U link (`…/get.php?username=…`) as well as with a login.

## 0.8.3
- The **Guide** button on the remote opens the TV guide from anywhere in the app; Back returns to where you were.
  While watching a channel it still shows that channel's schedule.
- Faster on TV boxes:
  - Guide updates read just the start of each guide file for its channel list, instead of the whole file twice.
  - Matching channels to guides does less work per channel.
  - Screens you've left (e.g. Home while watching) stop updating their "on now" details until you go back to them.

## 0.8.2
- Settings › TV guide sources is easier to use:
  - One status box, right under the buttons, shows what's happening while a guide is added, updated or matched,
    then says clearly how it went.
  - Matching says how many channels already have a guide and how many still have none, not just that nothing new
    matched.
  - Your guides, free country guides included, are all in one list. **Add a guide** opens a panel with the free
    guides by country (yours first) and a box for your own XMLTV address.
- Setting up the free channels now adds the free TV guide for each country you pick, where there is one.

## 0.8.1
- First launch asks what you'd like: add your TV provider, start with the free channels, or restore a backup.
  Nothing is set up until you choose.
- Settings › Languages & categories has a filter for countries, categories and hidden channels.
- Fixed: some free channels (e.g. BBC Four) closed the app. DASH streams now play, and any stream the app can't play
  shows a message instead.
- Fixed: a channel that kept failing just after starting (e.g. 5USA) looped on "Loading…" for ever. It now says it
  isn't playing after a few tries.

## 0.8.0
- **Catch-up TV** on channels whose provider offers it:
  - **Catch-up** in a channel's menu: the last few days, day by day; pick a programme to watch it.
  - **Start over**, in the channel menu and the player: watch what's on now from its beginning.
  - In the TV guide, press ◀ past "now" to scroll back on catch-up channels and press OK on a finished programme.
  - Catch-up plays on from one programme into the next, like the live channel only earlier. ◀ ▶ skip, and "Watch
    live" goes back to now.
- The TV guide loads exactly as before: catch-up listings are only fetched when you use catch-up.

## 0.7.0
- **Find it on demand from the TV guide**: hold OK (or press Info) on a programme.
  - **Find series** opens the series, on the episode that's on.
  - **Find in Movies** opens the film.
  - Anything else offers a search for its title. News and sport offer nothing.

## 0.6.0
- **Better TV guide matching**, especially for US local channels: call signs in brackets ("NBC 4 (WNBC) NEW YORK")
  match guide names like "US - NBC WNBC NY", and state names match their abbreviations.
- Settings › TV guide sources › **Match channels without a guide**: finds guides for channels that have none (90%
  match or better), shows its progress, and says how many it found.
- Guide updates are quicker: channels are only matched again when a guide is first added, when it changes, or for
  new channels. Matches you set by hand are never changed.
- Live TV opens on the list you used last.
- "Change guide source" in a channel's menu opens guide matching on just that channel.

## 0.5.0
- **Backup & restore** (Settings › Backup & restore): saves your playlists and logins, category and channel choices,
  favourites, TV guide sources and choices, watching progress and settings to Download/MyTVPlayer/mytvplayer-backup.json.
  Each backup replaces the last. No permissions needed. Copy the file with a file manager to keep it elsewhere.
- Restore from that file, or pick another one (e.g. copied from another TV). Channels, movies and the guide download again afterwards.

## 0.4.0
- **Search**: one box for channels (by name or number), what's on TV now and later, movies and series.
- Menus and side panels (channel options, audio, subtitles, guide) are the same size as the rest of the app.

## 0.3.3
- TV guide sources: free guides for 75 countries from iptv-epg.org, added with one press (your country first).
- The sources screen shows what an update is doing ("Downloading…", "Reading…", "Matching channels…").

## 0.3.2
- Movies and series appear after updating: they load on the next start, and untagged categories ("Action",
  "Netflix Movies") are included by default.
- "Update now" for each playlist in Settings › Playlists, and "Load now" when the Movies or Series list is empty.

## 0.3.1
- Settings › Playlists has an "Add free channels (iptv-org)" button, shown until the free channels are added.

## 0.3.0
- **Movies and Series** (Xtream accounts): browse by category with posters, filter by title, favourites,
  "Recently added", and pages with plot, cast and running time.
- Movies and episodes resume where you left off; the next episode starts on its own.
- **Carry on watching** on Home, including the next episode of a series you're watching.
- Settings › Languages & categories now has Live TV, Movies and Series tabs: only ticked categories are downloaded.
- The settings button is now a cog.

## 0.2.4
- Faster first setup of the free channels, and lighter on memory in the TV guide.
- Channels whose links don't say they're HLS streams now start first time when you come back to them.
- Country groups are recognised for every country (e.g. "Austria"), so the right ones are ticked automatically.

## 0.2.3
- The TV guide scrolls to earlier and later times with ◀ and ▶, keeping your place.
- Switch between the channel list and the TV guide with the remote's Guide button or from the channel menu, without losing your place.

## 0.2.2
- Holding OK on a channel opens its menu without starting the channel.
- The remote's Info or Menu button also opens the channel menu.
- Text is a step smaller (turn "Large text" on in Settings › Appearance for the bigger size); channel names get more room.

## 0.2.1
- Typing addresses, user names and passwords: no autocorrect, passwords shown as typed, and the keyboard's Next button moves to the next box.

## 0.2.0
- Free TV channels from iptv-org are set up on first launch, with your country's channels selected.
- M3U playlists can be added alongside Xtream accounts.

## 0.1.0
- First test release: Live TV list and guide, player with now/next, guide matching, playlists, settings.
