# Tentacle integration (Android TV)

Reference for the Tentacle parts of this app. The overview is in
[CLAUDE.md](../../CLAUDE.md); the plugin's full API (every route, who may
call it) is in jellyfin-tentacle's
[docs/agents/plugin-api.md](https://github.com/lucas-romanenko/jellyfin-tentacle/blob/main/docs/agents/plugin-api.md).
Checked against the code on 2026-09-28; the code wins where they differ.

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
  sections are Tentacle playlists (`HomeFragmentTentacleRow`, items
  prefetched in parallel), `"builtin"` ones map to Jellyfin's own sections by
  id (`constant/HomeSectionType.kt`: `latestmedia`, `smalllibrarytiles`,
  `librarybuttons`, `resume`, `resumeaudio`, `activerecordings`, `nextup`,
  `livetv`).
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
  releases, polled every 3 s, backing off to 60 s after failures; arr
  actions (search, grab, stop missing, remove) go through
  `/TentacleDiscover/Arr*`.
- **Navbar** (`ui/shared/toolbar/Navbar.kt`, `NavbarActiveButton`: User,
  Home, Library, Search, Discover, Activity, None): which buttons show and in
  what order comes from the Tentacle dashboard (`/TentacleHome/Toolbar`),
  not from local preferences; top bar or left sidebar
  (`LeftSidebarNavigation.kt`).
- **Item details**: the plugin's Detail endpoint merges `following`,
  `seriesStatus` and `canDelete` into `DiscoverDetail`, so the detail screen
  shows Follow and Delete without more calls. Delete (downloads only; admin,
  or the user who requested it) calls
  `DELETE /TentacleDiscover/LibraryItem/{type}/{id}?jellyfinItemId=` and
  navigates back at once.
- **Download notifications**: `pollNotifications()` fills
  `pendingNotifications` (a StateFlow), which `HomeFragment` shows as toasts;
  `dismissNotification(id)`.
- **Preferences** (`preference/UserSettingPreferences.kt`): media bar
  `mediaBarEnabled`, `mediaBarSourceType` (`"plugin"` = the Tentacle hero
  playlist only, no random fallback), `mediaBarContentType`,
  `mediaBarItemCount`, `mediaBarExcludedGenres`; `episodePreviewEnabled`,
  `previewAudioEnabled`. `UserPreferences.navbarPosition` (top or left).
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
