# Jellyfin Tentacle: Android TV

The Tentacle client for Android TV and Fire TV. A fork of
[Moonfin Android TV](https://github.com/Moonfin-Client/AndroidTV-FireTV)
(itself an enhanced fork of the official Jellyfin Android TV app) with the
[Tentacle](https://github.com/lucas-romanenko/jellyfin-tentacle) plugin
integration: curated home rows, a TMDB Discover tab, an Activity tab
(downloads and upcoming releases), an episode picker and following.
Without the plugin it behaves like the stock Moonfin home.

This repo is public and other people install its APKs: never put
credentials, tokens, internal IPs or server paths in it.

- Default branch `master`. On the workbench: `/code/jellyfin-tentacle-androidtv`;
  work in a worktree on a branch, merge to master, push.
- The previous app (a custom fork of the official client) is
  jellyfin-tentacle-androidtv-legacy (`/code/jellyfin-tentacle-androidtv-legacy`),
  for reference only.
- Tentacle integration details (repository, screens, the endpoints it
  calls): [docs/agents/tentacle-integration.md](docs/agents/tentacle-integration.md).

## Build and check

Builds run in CI, never on the workbench (a local build exhausts it; only
if Lucas asks): App / Build (`app-build.yaml`, `./gradlew assembleDebug`) on
every branch push and pull request. Pushing a branch is the way to check it;
read the result with
`gh run list -R lucas-romanenko/jellyfin-tentacle-androidtv --branch <branch> -L 1`.
Debug APKs are attached to pull requests only: to try a change on the TV,
open a draft PR and use its run's `build-artifacts`. A coding task on the
app ends with a TV test (the workbench's tv-test skill) when the TV is
available. With an SDK:

```
./gradlew assembleDebug            # github + playstore debug APKs
./gradlew assembleGithubRelease    # release APK (needs keystore.properties)
```

APKs land in `app/build/outputs/apk/{flavor}/{buildType}/`, named
`tentacle-androidtv-v{version}`.

- Toolchain: JDK 21 (`.tools-versions`), Kotlin 2.3.10, Compose, Leanback
  1.2.0, Jellyfin SDK 1.8.6, Media3 1.9.2, Koin 4.1.1, Coil 3.3.0, Ktor
  3.0.3, kotlinx.serialization 1.10.0 (`gradle/libs.versions.toml`).
- Flavors: `github` (self-update from GitHub releases) and `playstore`
  (no self-update).
- App id `org.jellyfin.tentacle` ("Tentacle"); debug adds `.debug`
  ("Tentacle Debug") so both install side by side. The Kotlin namespace stays
  `org.jellyfin.androidtv`. Client name sent to Jellyfin: "Tentacle Android
  TV" (`di/AppModule.kt`, `di/AuthModule.kt`).
- R8: release minifies and shrinks; debug doesn't (fast builds, readable
  stack traces). So a bug can exist only in the release APK: test the
  release build before calling a change done. Keep rules are package-wide
  in `app/proguard-rules.pro` (`org.jellyfin.androidtv.**`,
  `org.jellyfin.playback.**`, `org.jellyfin.preference.**`,
  `org.jellyfin.design.**`, `org.tentacle.**`), because R8 full mode breaks
  Koin, kotlinx.serialization and Compose.
- Version: `tentacle.version` in `gradle.properties` for local builds; a
  release takes it from the tag (`TENTACLE_VERSION`, environment first,
  `buildSrc/src/main/kotlin/Properties.kt`). Version code =
  major·1000000 + minor·10000 + patch·100 + pre-release
  (`buildSrc/src/main/kotlin/VersionUtils.kt`).

## Modules

```
app/               the Android TV app (org.jellyfin.androidtv)
design/            design system (Compose tokens, typography)
server/core        server-agnostic interfaces (org.tentacle.*)
server/jellyfin    Jellyfin implementation
server/emby        Emby implementation (+ EmbyCompatInterceptor)
playback/core      playback abstraction; playback/jellyfin, playback/emby
playback/media3/   exoplayer and session integration
preference/        preferences library
buildSrc/          Gradle helpers (VersionUtils.kt, Properties.kt)
```

Key files under `app/src/main/java/org/jellyfin/androidtv/`:

```
JellyfinApplication.kt                  entry point, Koin init
di/AppModule.kt, di/AuthModule.kt       DI: SDK, repositories, image loader, ViewModels; auth
data/repository/TentacleRepository.kt   the only client of the Tentacle plugin (OkHttp)
ui/home/HomeFragment.kt                 home: Compose overlay (navbar, hero, media bar) over Leanback rows
ui/home/HomeRowsFragment.kt             Tentacle sections, or the stock rows without the plugin
ui/home/HomeFragmentTentacleRow.kt      a Tentacle playlist row
ui/discover/                            Discover tab, detail modal, EpisodePickerDialog
ui/activity/ActivityFragment.kt         Activity tab
ui/navigation/Destinations.kt           routes
ui/shared/toolbar/Navbar.kt             top navbar (buttons from the Tentacle dashboard)
constant/HomeSectionType.kt             Jellyfin built-in home section ids
auth/repository/SessionRepository.kt    multi-server session state
auth/repository/ServerRepository.kt     known servers (Jellyfin 10.8+, Emby 4.8+)
auth/store/AuthenticationStore.kt       stored credentials (server -> user -> token)
```

Inherited from Moonfin and still here: Emby support and multi-server
aggregation (`MultiServerRepository`), MDBList ratings, trailer previews
(NewPipe Extractor, SponsorBlock), SyncPlay, screensaver, folder view,
self-update (github flavor). Removed since the fork: Jellyseerr/Seerr and
the seasonal effects.

## Gotchas

- Tentacle calls use OkHttp directly with `?userId=&api_key=` (the user's
  own Jellyfin token); the plugin checks the token is that user's. Any new
  endpoint must be user-scoped on the plugin side, and admin-only ones
  (`/Tentacle/Refresh`) fail with 403 for ordinary users.
- The hero is a Compose overlay on a Leanback `RowsSupportFragment`: two UI
  toolkits share `HomeFragment`; set backgrounds in both (the Compose
  `background` token, `colors.xml`, `theme_jellyfin.xml`, the activity
  layouts).
- Tentacle row images come from Jellyfin's image API (the items are plain
  `BaseItemDto`); Discover images come from the TMDB CDN.
- Jellyfin user ids have dashes; the Tentacle server stores them without.
- The Android TV launcher caches banner images hard: a new banner needs
  uninstall, reboot, reinstall.

## Releasing is Lucas's decision

APKs go to other people's TVs, so only Lucas tags or creates releases. A
Claude session may prepare release notes (`git log vA.B.C..master`) and say
"ready to release"; it never creates a tag or release itself.

Lucas's steps: set `tentacle.version` in `gradle.properties` to the new
version and merge that to master, then tag it (`git tag -a v1.20.3 -m v1.20.3
&& git push origin v1.20.3`) or create a GitHub release on a new tag.
App / Release (`app-release.yaml`) builds `assembleGithubRelease`, signed
with the production keystore from the repository secrets (`KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEYSTORE_ALIAS`, `KEYSTORE_KEY_PASSWORD`; it fails
without them, because an APK signed with another key can't update an
installed one), and attaches the APK to the tag's release: a release Lucas
wrote keeps its title and notes; a bare tag gets one with generated notes.
Pushes to master publish nothing.

Check: `gh release view v1.20.3 -R lucas-romanenko/jellyfin-tentacle-androidtv`
lists `tentacle-androidtv-v1.20.3-github-release.apk`, and the App / Release
run succeeded.
