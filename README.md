# Jellyfin Tentacle - Android TV

Custom Android TV client for Jellyfin with [Tentacle](https://github.com/lucas-romanenko/jellyfin-tentacle) integration. Based on the official [Jellyfin Android TV](https://github.com/jellyfin/jellyfin-androidtv) app.

## What's different

- **Tentacle home screen** — Home rows and hero spotlight are driven by the Tentacle dashboard instead of Jellyfin's default home sections
- **Discover tab** — Browse trending, popular, and upcoming movies/series from TMDB, and add them to Radarr/Sonarr directly from the TV with quality profile selection
- **Activity tab** — Real-time download queue from Radarr/Sonarr with progress bars, plus upcoming releases with countdown badges
- **Playlist rows** — Tentacle-managed playlists (auto and custom) appear as native Leanback rows with poster cards
- **Custom branding** — Tentacle splash screen, toolbar logo, dark navy theme (`#0F0D1A`), redesigned connect/login screens

## Requirements

- A Jellyfin server with the [Tentacle plugin](https://github.com/lucas-romanenko/jellyfin-tentacle) installed
- Android TV, Nvidia Shield, Amazon Fire TV, or Chromecast with Google TV

## Building

Requires Android Studio and the Android SDK.

```shell
./gradlew assembleDebug
```

The APK is output to `app/build/outputs/apk/debug/`.

## Architecture

This is a fork of `jellyfin/jellyfin-androidtv`. Key additions:

| File | Purpose |
|------|---------|
| `TentacleRepository.kt` | API client for the Tentacle Jellyfin plugin endpoints |
| `HomeFragmentTentacleRow.kt` | Leanback row renderer for Tentacle playlist content |
| `DiscoverFragment.kt` | Discover tab UI (trending, search, detail modal, add to Radarr/Sonarr) |
| `ActivityFragment.kt` | Activity tab (download queue + upcoming releases) |
| `HeroSpotlight.kt` | Full-screen hero banner on the home screen |

The app communicates with the Tentacle plugin via its custom Jellyfin API endpoints (`/TentacleHome/*`, `/TentacleDiscover/*`). If the plugin is not installed, the app falls back to standard Jellyfin home sections.

## License

Licensed under the [GNU General Public License v2.0](LICENSE), same as the upstream Jellyfin Android TV project.
