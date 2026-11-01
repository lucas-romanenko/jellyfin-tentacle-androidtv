# Tentacle integration (Android TV)

Reference for the Tentacle parts of this app. The overview is in
[CLAUDE.md](../../CLAUDE.md); the plugin's full API (every route, who may
call it) is in jellyfin-tentacle's
[docs/agents/plugin-api.md](https://github.com/lucas-romanenko/jellyfin-tentacle/blob/main/docs/agents/plugin-api.md).
The code wins where they differ.

## TentacleRepository

`app/src/main/java/org/jellyfin/androidtv/data/repository/TentacleRepository.kt`
(about 1900 lines) is the only client of the plugin.

- OkHttp directly, not the Jellyfin SDK: the plugin's endpoints aren't in the
  SDK. The client comes from Koin: `TentacleRepository(androidContext(),
  get(), get(), OkHttpFactory.createClient(...))` in `di/AppModule.kt`.
- Every URL is built by `buildUrl(path)`: the server's base URL, then
  `?userId={current user id}&api_key={the user's Jellyfin access token}`.
  The plugin checks that the token belongs to that user (`CallerIdentity`),
  so the app can only ever act as the signed-in user.
- JSON: `kotlinx.serialization` with `ignoreUnknownKeys = true` and
  `coerceInputValues = true`, so new or null fields from the plugin don't
  break older apps. All calls are `suspend` on `Dispatchers.IO`.
- `checkAvailable()` is cached per session (`availabilityChecked`); without
  the plugin the app falls back to the stock home screen.
- The `@Serializable` models live at the bottom of the file (51 data
  classes: sections, discover, activity, fix-match, arr actions, seasons and
  episodes, notifications, toolbar). R8 keeps them via the package-wide
  `-keep class org.jellyfin.androidtv.** { *; }` in `app/proguard-rules.pro`.

## Screens

- **Home** (`ui/home/`): `HomeFragment` hosts the Compose overlay (navbar,
  hero, media bar) over the Leanback `HomeRowsFragment`, which asks
  `/TentacleHome/Sections` and renders them in the API's order: `"row"`
  sections are Tentacle playlists (`HomeFragmentTentacleRow`). Every row is
  Tentacle's (no Jellyfin home sections since 2026-10-26): Continue Watching
  (`resumevideo`, with next episodes when on), Next Up (`nextup`, the next
  episodes on their own row, only while Continue Watching's are off) and
  Recordings come as `"builtin"` sections with a `shape`;
  `withTentacleListRows()` turns them into rows with the id
  `tentacle-<section>` whose items are the plugin's
  `/TentacleHome/ContinueWatching`, `/NextUp` and `/Recordings`, so they load, refresh
  in place and change shape like playlist rows, and are asked again on every
  return to Home (`refreshTentacleListRows`: a playback changes them without
  a home version). `onnow` is Tentacle's On Now (`ui/home/OnNow.kt`): the
  person's favourite channels with what's on now
  (`/LiveTv/Channels?isFavorite&addCurrentProgram` as them, so kids get kids
  channels only), one square card per channel (`ImageType.SQUARE`, the logo
  whole) named after the programme with the channel and time left below; it
  tunes in like any channel, leaves out when nothing is on, and is refilled
  on every home refresh. An older plugin's `"builtin"` sections (no shape)
  still map to Jellyfin's own rows (`constant/HomeSectionType.kt`).
- **Home's first load** (audit H4): `/Sections` is asked once, alongside
  `/UserViews` (no separate availability probe; `fetchSections()` settles
  it). Its `"hero"` section decides the media bar, which fetches
  `/HeroConfig` and `/Hero` itself, in parallel, and publishes the
  dashboard's trailer sound (`heroTrailerAudio`, applied by `HomeFragment`).
  Every row goes on screen at once: Tentacle rows as empty placeholders whose
  items are fetched 4 at a time and filled in as each arrives
  (`fillTentacleRows`; an empty one leaves), stock rows loading themselves.
  The hero and the first two rows are asked first; the other rows wait for
  the hero's answer (3 s at most): on the Sony 20 row answers decoding
  at once held the hero, and so the loading screen, back by 5–7 s.
  The loading screen waits for the hero and the first two content rows
  (`firstScreenRows`), with no fixed hold. `HomeTiming` logs the milestones
  (`adb logcat -s HomeTiming`, ms since the process started).
- **Speed rules** (audit H5, H6, M1, M2): background art is asked at most
  960 px wide (480 on a low-RAM device), only the first backdrop while
  browsing, blurred once on the CPU there (`BackgroundService`). Only the latest
  background request reaches the screen (`BackdropRequests`, `BackdropRequestsTests`):
  an older load landing late is dropped, a clear wins, and another title's picture fades
  to neutral after 1 s while the focused one loads (#14). Card
  previews start after focus and the D-pad rested 2 s (`PreviewRest.kt`),
  play on one shared muted player (`CardPreviewPlayer`). Stock rows ask
  `ItemRepository.browseFields` (no media sources, streams, chapters,
  trickplay), Latest 20 per library. A row refresh diffs its cards
  (`CardDiff`): the same answer changes nothing on screen.
- **Live settings**: `SocketHandler` (on while the app is in front) reads
  the home version from the plugin's LibraryChanged push into
  `HomeVersionTracker`, and reports WebSocket reconnects. Home and both
  navbars ask again (`/Sections`, `/Toolbar` carry `homeVersion`) when they
  come back into view, when the app comes back to the front and on a
  reconnect, and refresh only if the version moved. Home refreshes go
  through one queue (`SerialRefresher`: one at a time, newest wins, none
  before the first load). `fetchSections()` tells "home off" (a 200 with
  `enabled: false`) from "no answer": off shows the stock rows. With an
  older plugin (no version) everything works as before.
- **Discover** (`ui/discover/`: `DiscoverFragment`, `DiscoverBrowse`,
  `DiscoverComponents`): TMDB browse and search through the plugin, a detail
  modal, add to Radarr/Sonarr with a quality profile (the last one chosen is
  kept in the `tentacle` SharedPreferences), follow/unfollow series (Sonarr
  "monitor new items"). TMDB images straight from the CDN
  (`image.tmdb.org/t/p/w342` posters, `w1280` backdrops).
- **Search tab** (`ui/search/`): Jellyfin channels and library rows, then
  Discover (`/TentacleDiscover/Search`). Typing waits 350 ms
  (`SearchQueries`); Discover needs 2 letters. A newer query cancels the
  older searches, HTTP call included (`executeCancellable`), and
  `DiscoverSearchState` ignores late answers and keeps the last good
  titles when a search fails or times out: the "busy" line shows only
  when Discover has nothing on screen (`DiscoverSearchStateTests`).
- **Requests that wait for approval** (family members; pure functions in
  `data/repository/FamilyRequests.kt`, `FamilyRequestsTests`):
  `/RequestMode` (`{"mode":"ask"|"direct","approver"}`, asked once per
  session, 3 s; a 404 or failure is "direct") makes the Discover add
  buttons read "Request". An add answered with `pending > 0` (HTTP 200,
  `detail` = "Sent to <admin> to approve…") is a success: the `detail`
  (else "Sent for approval") shows in green and the button reads
  "Requested" (`addOutcome`, `addEpisodesOutcome`).
- **Guide live preview**: `/LivePreview` (`?previewing=true` while this TV's preview runs;
  `{"previews","limit","active","free","recordings_soon","known"}`, 3 s, every 15 s while the
  guide is on screen; no answer keeps what is shown). `previews` false = no box in the guide
  header (`ui/livetv/GuidePreview.kt`, `data/repository/LivePreview.kt`).
- **Problems in plain words**: `/FamilyStatus`
  (`{"messages":[{"area":"livetv"|"requests"|"library","text"}]}`, 3 s,
  failures ignored). `ui/livetv/LiveTvStatusNotice.kt` shows the `livetv`
  text when the guide opens, instead of "Unable to load channels", and
  next to the player's "Error streaming live TV" (same text at most once
  per 30 s).
- **Live TV guide** (`ui/livetv/LiveTvGuideFragment.java`, pure helpers in
  `ui/livetv/GuideModel.kt`, `GuideModelTests`; plain Jellyfin, no plugin
  call): Live TV (the library tile, the sidebar, `ItemLauncher`) opens
  straight to the guide; the old `LiveTvBrowseFragment` landing is no
  longer reached. Favorites / All at the top: Favorites asks
  `LiveTv/Channels?isFavorite=true` (fast, plus a `limit=1` count of all),
  All is `TvManager.loadAllChannels`. The choice is Jellyfin display
  preferences `tentacle-livetv` / client `tentacle` / CustomPrefs
  `guideView` = `favorites` | `all`, the same as Tentacle's web guide, so
  it follows the person. Days: today up to the furthest programme end
  (`POST LiveTv/Programs`, StartDate descending, Limit 1, favourite ids in
  the body for Favorites); one day of data = no day chips, and paging right
  stops there. One Recordings button in the header opens the Recordings
  screen (`ui/browsing/v2/RecordingsBrowseFragment.kt`, #203): built like
  the guide, with a Recorded | Scheduled | Series switch (the last two for
  people who may manage recordings, `enableLiveTvManagement`);
  `Destinations.liveTvRecordings` / `liveTvSchedule` /
  `liveTvSeriesRecordings` open its parts. Recorded is one paged
  `LiveTv/Recordings` read sorted into rows by `groupRecordings`
  (`RecordingsLayout.kt`, `RecordingsLayoutTests`); a recording's air time
  comes from its file name (`yyyy_MM_dd_HH_mm_ss`), since recordings carry
  no StartDate. Holding OK on a channel name favourites it.
  A recording still recording (#239, `ui/playback/GrowingRecording.kt`,
  `GrowingRecordingTests`): OK asks Watch live / Resume / Play from start
  (`recordingNowChoices`), all on the recording's file in one player
  (Lucas, 30 Oct): Watch live starts at its live end (start position
  `GROWING_LIVE_EDGE_MS`; once the remux grows at about real time,
  `growingCaughtUp`, the player seeks to the end), Jump to live seeks there
  again. Jellyfin swaps in its own source while it
  records (another id, File, infinite, no runtime), only as HLS: an EVENT
  `live.m3u8` that ffmpeg remuxes (copy) from 0:00 of the growing .ts and
  appends to. PlaybackInfo gets no MediaSourceId (the item's own id gives
  NoCompatibleStream) and StartTimeTicks 0; the player starts at the
  window's 1 ms (Media3 takes 0 for "default", the live edge) and seeks
  itself, never past what's written; its length is the player's window,
  never the item's runtime (a scan can probe one from the half-written
  file). When the recording stops, the playlist stops growing
  (PlaylistStuckException) and the normal retry gets the finished file. Reports carry no
  position and the stop is `Failed` (else Jellyfin marks a no-runtime item
  watched at the first report); the position is saved with
  `UserItems/{id}/UserData`. Needs the server's EnableSegmentDeletion off
  (default).
  Live updates (`ui/livetv/GuideLiveUpdates.kt`, pure logic in `GuideModel.kt`,
  `GuideLiveModelTests`), while the guide is started: the websocket's
  `UserDataChanged` (Jellyfin sends it ~1 s after any favourite change,
  whoever made it, Tentacle's server included) changes the stars, and in
  Favorites removes or adds just those rows (a starred channel not shown
  re-reads the favourites list); `LibraryChanged` (via SocketHandler and
  `HomeVersionTracker.changes`; Tentacle marks its Live TV setup runs
  `tentacle-livetv:<version>`) re-reads the channel list after 1.5 s of
  quiet and applies only what differs; a socket reconnect or the guide's
  `onResume` re-reads once. Rows change in place
  (`LiveTvGuideFragment.applyLiveChannels`): focus stays on its row and
  programme (the nearest row when its row goes), the screen doesn't scroll.
  Back with focus in the grid (channel names, programmes, the paging
  buttons) moves focus to the top row's shown switch (`GuideBackToTop.kt`),
  as Back on Home goes to the navbar; Back there leaves the guide. The grid
  keeps its place, and the first Down from the top row returns to the cell
  Back left. The Recordings screen does the same (Back on a card → its
  switch; Down from the top bar → the Recorded card Back left).
- **Multi-view** (`ui/livetv/multiview/`, server `services/multiview.py`):
  the guide's Multi-view chip (next to Recordings) asks
  `GET /TentacleDiscover/MultiView` (free connections; a Toast says why
  not) and puts the guide in pick mode (`GuideMultiViewPick.kt`): OK on a
  channel or its programme ticks it (`pickBadge` 1-4 on the row, OK again
  unticks; `MultiViewPicks`), the chip reads "2 of 4 · Start", Play starts
  too, Back cancels (an `OnBackPressedCallback` added after
  `GuideBackToTop`); the bar covers Filters, so pick mode makes it
  invisible (else Up/Down from the chip landed on it). Start posts the Jellyfin channel ids; Tentacle answers
  a session with one 1080p MPEG-TS picture (2 side by side, 3-4 as 2x2)
  and one audio track per tile. `MultiViewFragment` plays it with its own
  ExoPlayer; the D-pad moves the highlight (`TileGrid`) and selects that
  tile's audio track (`TrackSelectionOverride` on the n-th audio group); OK
  = that channel through the normal player (`retrieveAndPlay`; its upstream
  is open on the server, so no new connection), Back there returns here;
  hold OK / Menu = full screen, swap (favourites), remove (`PUT`). A
  keepalive every 15 s while the app is in front (the fragment stays in the
  history, detached, while a channel plays); `onDestroy` (Back here, or
  the history cleared) sends `DELETE`, and so does the app leaving the
  foreground (the activity's `ON_STOP`: Home, another input or app; coming
  back opens the guide, not a grid the server was about to drop). The
  server closes a session alone after 45 s without a reader or keepalive.
  While the grid's player plays it holds a screensaver lock
  (`KeepAwakeWhilePlaying` → `InteractionTrackerViewModel.addLifecycleLock`,
  as VideoManager does for the normal player), given back when it stops,
  fails or is released (Back, Home, full screen). The guide header's live
  preview takes no lock on purpose: it's a muted box while browsing, and the
  screensaver coming on (the app stops) is what closes its connection.
- **Favorites** (`ui/browsing/v2/FavoritesBrowseFragment.kt`, the navbar's
  heart; plain Jellyfin, no plugin call): Lucas wants only three rows, no
  header ("row for favorite movies, row for favorite shows and row for
  favorite channels"; a header with the focused card's description covered
  the cards). The title with Home and the clock, then Films, Shows and
  Channels (`FavoriteRowKind`, `favoriteRows` in `FavoritesLayout.kt`,
  `FavoritesLayoutTests`), sized so all three fit on the screen whole
  (posters 72 × 108, channels 144 × 81). Films leave out recordings and
  YouTube videos (`isFavoriteYouTubeVideo`: a `yt:` tag, the YouTube genre, a
  video id or the .strm folder, never the bare TMDB `youtube` tag). Channels
  are the guide's favourites (`LiveTv/Channels?isFavorite&addCurrentProgram`,
  in the guide's order): logo, number, what's on, time left; OK tunes. An
  empty row isn't shown. Two reads at once. Live, in place: `UserDataChanged`
  that adds or removes a favourite (`favoritesChanged`), `LibraryChanged`
  after 1.5 s, a socket reconnect, every minute (what's on now), and back
  from a details page. The ViewModel keeps the rows' scroll and the last card
  (back from a details page focus returns to it; a removed card's neighbour
  takes it). Back on a card → Home button; Back there leaves; Down returns
  to the card.
- **Episode picker** (`ui/discover/EpisodePickerDialog.kt`,
  `EpisodePickerMode`): `ADD_NEW` (add a series with chosen episodes),
  `DOWNLOAD_MORE` (VOD series: download missing episodes), `MANAGE` (change
  what Sonarr monitors).
- **Activity** (`ui/activity/ActivityFragment.kt`): downloads and upcoming
  releases, from the app's one `ActivityPoller` (M9; also the navbar's
  download badge): every 3 s while the tab is open, 10 s while something
  downloads, else 30 s, backing off after failures; arr
  actions (search, grab, stop missing, remove) go through
  `/TentacleDiscover/Arr*`. With `for_names` in the answer, someone else's
  item (its `for`) shows "for <name>" on every card (`forLabel`); without it
  (older servers) only Searching shows `requested_by`.
- **Live push**: the plugin sends a `RefreshProgress` with
  `ItemId: "tentacle"` and `Tentacle: "requests,activity,notifications"` (the
  topics that changed). `SocketHandler` (`tentaclePushTopics`) polls the
  `ActivityPoller` at once for requests/activity and fetches notifications at
  once for notifications, and does both when the WebSocket reconnects. Every
  other `RefreshProgress` (Jellyfin's library scans) is ignored; the polls
  stay as the fallback.
- **Navbar** (`ui/shared/toolbar/Navbar.kt`, `NavbarActiveButton`: User,
  Home, Library, Search, Discover, Activity, None): which buttons show and in
  what order comes from the Tentacle dashboard (`/TentacleHome/Toolbar`),
  not from local preferences; top bar or left sidebar
  (`LeftSidebarNavigation.kt`). A new screen's navbar starts from the
  session's last answer (`TentacleRepository.lastToolbar`) and asks again
  only when the home version moved (`toolbarIsCurrent`).
- **Item details**: the plugin's Detail endpoint merges `following`,
  `seriesStatus` and `canDelete` into `DiscoverDetail`, so the detail screen
  shows Follow and Delete without more calls. Delete (downloads only; admin,
  or the user who requested it) calls
  `DELETE /TentacleDiscover/LibraryItem/{type}/{id}?jellyfinItemId=` in the
  app's scope (leaving the page doesn't cancel it) and waits for Tentacle's
  answer (`deleteOutcome()` in `data/repository/LibraryDelete.kt`): a 2xx
  shows "<title> deleted" and goes back (only if the page is still shown);
  a 404 says the title is no longer in Tentacle's library; a 4xx or 502
  with a JSON `detail` shows Tentacle's own reason; anything else (no
  answer, other 5xx, no `detail`) says Tentacle can't be reached. The page
  stays open on every answer but a 2xx. A title with a TMDB id never falls
  back to a Jellyfin delete; only an item without one is deleted through
  Jellyfin directly (#69).
- **Download notifications**: `pollNotifications()` fills
  `pendingNotifications` (a StateFlow), which MainActivity shows as toasts
  on every screen but the players (`ui/notification/NotificationToast.kt`;
  its heading adds "for <name>" from the notification's `for`). A toast
  shows only while the app is in front, off a player and with no
  screensaver; `dismissNotification(id)` only after it was on screen 8 s or
  the user closed or opened it, never for one nobody saw (it waits and
  shows later). Polled every 15 s while a toast could show, plus the push.
- **YouTube videos** (`util/sdk/TentacleYouTube.kt`, `TentacleYouTubeTests`):
  Tentacle writes each one as a Movie (genre `YouTube`, tag `youtube`,
  provider id `youtube`, studio = channel, path
  `<root>/<Channel>/<YYYY-MM-DD Title [id]>/…strm`). `isTentacleYouTube`
  takes any of these, since each screen asks different fields (Tentacle rows
  have only Genres and Path); every card subtitle and meta line (cards'
  `getSubName`, home info row, hero, library grid and HUD, search, genre and
  favourites browse, details) shows `youTubeSubtitle`, "YouTube · <channel>"
  (studio, else the channel folder), or nothing, never "Movie • 2026".
- **Preferences** (`preference/UserSettingPreferences.kt`): media bar
  `mediaBarEnabled`, `mediaBarSourceType` (`"plugin"` = the Tentacle hero
  playlist only, no random fallback), `mediaBarContentType`,
  `mediaBarItemCount`, `mediaBarExcludedGenres`; `episodePreviewEnabled`,
  `previewAudioEnabled` (the hero trailer's sound; card previews are always
  muted). `UserPreferences.navbarPosition` (top or left).
  Toolbar buttons come from the dashboard, not preferences; the old
  `showShuffleButton` is still read in two places.
- Routes: `ui/navigation/Destinations.kt` (`tentacleDiscover` is
  `ui.discover.DiscoverFragment`, imported as `TentacleDiscoverFragment`;
  `tentacleActivity` is `ActivityFragment`).

## Endpoints the app calls

From `TentacleRepository.kt` (including `arrAction("...")` calls). Auth as
the plugin enforces it: `user` = the signed-in user's token; `admin` needs
a Jellyfin admin. `refreshPluginCache()` (`/Tentacle/Refresh`) is admin-only
and has no callers: don't wire it to a button for ordinary users.

| Method | Path | Auth |
|---|---|---|
| POST | `/Tentacle/Refresh` | admin |
| GET | `/TentacleDiscover/Activity` | user |
| POST | `/TentacleDiscover/AddToRadarr` | user |
| POST | `/TentacleDiscover/AddToSonarr` | user |
| POST | `/TentacleDiscover/ArrCheck` | user |
| POST | `/TentacleDiscover/ArrGrab` | user |
| POST | `/TentacleDiscover/ArrRemove` | user |
| POST | `/TentacleDiscover/ArrSearch` | user |
| POST | `/TentacleDiscover/ArrStopMissing` | user |
| GET | `/TentacleDiscover/Detail/{mediaType}/{tmdbId}` | user |
| GET | `/TentacleDiscover/DetailTvdb/{tvdbId}` | user |
| GET | `/TentacleDiscover/FamilyStatus` | user |
| GET | `/TentacleDiscover/LivePreview` | user |
| POST | `/TentacleDiscover/FixMatch/movie/{tmdbId}` | user |
| GET | `/TentacleDiscover/FixMatch/movie/{tmdbId}/Frames` | user |
| GET | `/TentacleDiscover/FixMatch/movie/{tmdbId}/Suggestions` | user |
| POST | `/TentacleDiscover/Follow/{tmdbId}` | user |
| GET | `/TentacleDiscover/Genre` | user |
| GET, POST | `/TentacleDiscover/MultiView` | user |
| PUT, DELETE | `/TentacleDiscover/MultiView/{id}` | user |
| POST | `/TentacleDiscover/MultiView/{id}/Keepalive` | user |
| GET | `/TentacleDiscover/MultiView/{id}/Stream` | none (the session id) |
| GET | `/TentacleDiscover/Genres` | user |
| GET | `/TentacleDiscover/Items` | user |
| DELETE | `/TentacleDiscover/LibraryItem/{mediaType}/{tmdbId}` | user |
| GET | `/TentacleDiscover/ListMissing` | user |
| GET | `/TentacleDiscover/Lists` | user |
| POST | `/TentacleDiscover/ManageEpisodes` | user |
| GET | `/TentacleDiscover/Notifications` | user |
| POST | `/TentacleDiscover/Notifications/{notificationId}/Dismiss` | user |
| GET | `/TentacleDiscover/Providers` | user |
| GET | `/TentacleDiscover/RadarrProfiles` | user |
| POST | `/TentacleDiscover/ReplaceCopy/{mediaType}/{tmdbId}` | user |
| GET | `/TentacleDiscover/RequestMode` | user |
| GET | `/TentacleDiscover/Search` | user |
| GET | `/TentacleDiscover/Season/{tmdbId}/{seasonNumber}` | user |
| GET | `/TentacleDiscover/SeasonTvdb/{tvdbId}/{seasonNumber}` | user |
| GET | `/TentacleDiscover/Seasons/{tmdbId}` | user |
| GET | `/TentacleDiscover/SeasonsTvdb/{tvdbId}` | user |
| GET | `/TentacleDiscover/SonarrEpisodes/{tmdbId}` | user |
| GET | `/TentacleDiscover/SonarrProfiles` | user |
| GET | `/TentacleDiscover/Streaming` | user |
| GET | `/TentacleDiscover/VodEpisodes/{tmdbId}` | user |
| POST | `/TentacleDiscover/WrongMatch/movie/{tmdbId}` | user |
| GET | `/TentacleHome/ContinueWatching` | user |
| GET | `/TentacleHome/Hero` | user |
| GET | `/TentacleHome/NextUp` | user |
| POST | `/TentacleHome/Hero` | user |
| GET | `/TentacleHome/HeroConfig` | user |
| GET | `/TentacleHome/Playlists` | user |
| GET | `/TentacleHome/Recordings` | user |
| POST | `/TentacleHome/Reorder` | user |
| GET | `/TentacleHome/Section/{playlistId}` | user |
| GET | `/TentacleHome/Sections` | user |
| GET | `/TentacleHome/Toolbar` | user |
