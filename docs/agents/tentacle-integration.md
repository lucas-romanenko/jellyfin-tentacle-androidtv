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
  sections are Tentacle playlists (`HomeFragmentTentacleRow`), `"builtin"`
  ones map to Jellyfin's own sections by id (`constant/HomeSectionType.kt`:
  `latestmedia`, `smalllibrarytiles`, `librarybuttons`, `resume`,
  `resumeaudio`, `activerecordings`, `nextup`, `livetv`).
- **Home's first load** (audit H4): `/Sections` is asked once, alongside
  `/UserViews` (no separate availability probe; `fetchSections()` settles
  it). Its `"hero"` section decides the media bar, which fetches
  `/HeroConfig` and `/Hero` itself, in parallel, and publishes the
  dashboard's trailer sound (`heroTrailerAudio`, applied by `HomeFragment`).
  Every row goes on screen at once: Tentacle rows as empty placeholders whose
  items are fetched together (4 at a time) and filled in as each arrives
  (`fillTentacleRows`; an empty one leaves), stock rows loading themselves.
  The loading screen waits for the hero and the first two content rows
  (`firstScreenRows`), with no fixed hold. `HomeTiming` logs the milestones
  (`adb logcat -s HomeTiming`, ms since the process started).
- **Speed rules** (audit H5, H6, M1, M2): background art is asked at most
  960 px wide (480 on a low-RAM device), only the first backdrop while
  browsing, blurred once on the CPU there (`BackgroundService`). Card
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
- **Episode picker** (`ui/discover/EpisodePickerDialog.kt`,
  `EpisodePickerMode`): `ADD_NEW` (add a series with chosen episodes),
  `DOWNLOAD_MORE` (VOD series: download missing episodes), `MANAGE` (change
  what Sonarr monitors).
- **Activity** (`ui/activity/ActivityFragment.kt`): downloads and upcoming
  releases, from the app's one `ActivityPoller` (M9; also the navbar's
  download badge): every 3 s while the tab is open, 10 s while something
  downloads, else 30 s, backing off after failures; arr
  actions (search, grab, stop missing, remove) go through
  `/TentacleDiscover/Arr*`.
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
  `pendingNotifications` (a StateFlow), which `HomeFragment` shows as toasts;
  `dismissNotification(id)`.
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
| POST | `/TentacleDiscover/FixMatch/movie/{tmdbId}` | user |
| GET | `/TentacleDiscover/FixMatch/movie/{tmdbId}/Frames` | user |
| GET | `/TentacleDiscover/FixMatch/movie/{tmdbId}/Suggestions` | user |
| POST | `/TentacleDiscover/Follow/{tmdbId}` | user |
| GET | `/TentacleDiscover/Genre` | user |
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
| GET | `/TentacleHome/Hero` | user |
| POST | `/TentacleHome/Hero` | user |
| GET | `/TentacleHome/HeroConfig` | user |
| GET | `/TentacleHome/Playlists` | user |
| POST | `/TentacleHome/Reorder` | user |
| GET | `/TentacleHome/Section/{playlistId}` | user |
| GET | `/TentacleHome/Sections` | user |
| GET | `/TentacleHome/Toolbar` | user |
