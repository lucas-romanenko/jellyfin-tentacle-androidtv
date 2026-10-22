# Releasing

APKs go to other people's TVs, so only the maintainer tags or creates
releases. Release tags (`v*`) are protected by a GitHub ruleset; the
maintainer makes them with a private tool (`tentacle-tag`, documented in
~/homelab/docs/tentacle.md "Rulesets and releases", Lucas's server,
private). Pushes to master publish nothing.

## Version

- `tentacle.version` in `gradle.properties` for local builds; a release
  takes it from the tag (`TENTACLE_VERSION`, environment first,
  `buildSrc/src/main/kotlin/Properties.kt`).
- Version code = major·1000000 + minor·10000 + patch·100 + pre-release
  (`buildSrc/src/main/kotlin/VersionUtils.kt`).

## Release notes (what a session prepares)

User-visible changes, issue numbers in brackets, from `git log
vA.B.C..master`. End with "ready to release vX.Y.Z".

## Steps (maintainer)

1. Set `tentacle.version` in `gradle.properties` to the new version and
   merge that to master (a pull request).
2. Tag it: `tentacle-tag jellyfin-tentacle-androidtv vX.Y.Z` (checks the
   commit is on master and its `Build` passed, then pushes an annotated tag).
3. Release notes on the existing tag afterwards (GitHub UI or `gh release
   create vX.Y.Z --verify-tag`).

App / Release (`app-release.yaml`) runs on the tag: it builds
`assembleGithubRelease`, signed with the production keystore from the
repository secrets (`KEYSTORE_*`, see the workflow; it fails without them,
because an APK signed with another key can't update an installed one), and
attaches the APK to the tag's release, twice: the versioned
`tentacle-androidtv-vX.Y.Z-github-release.apk` and a copy named
`tentacle-androidtv-github-release.apk`, so
`https://github.com/lucas-romanenko/jellyfin-tentacle-androidtv/releases/latest/download/tentacle-androidtv-github-release.apk`
is always the newest release (installs with the Downloader app; the
Tentacle server's `/tv` redirects there). A release the maintainer wrote
keeps its title and notes; a bare tag gets one with generated notes.

## How installed apps update

The github flavor's release build checks
`api.github.com/repos/lucas-romanenko/jellyfin-tentacle-androidtv/releases/latest`
(`data/service/UpdateCheckerService.kt`; decisions as pure functions in
`UpdateLogic.kt`, `UpdateLogicTests`) when Home shows and the last check
the person saw is 6 h old, or at once when the daily `UpdateCheckWorker`
found a newer version (`isHomeCheckDue`; not per process: WorkManager
starts the app in the background, and opening it later must still check),
and offers a strictly newer tag (semver: an rc is older than
its release) in a dialog (`ui/update/UpdatePrompt.kt`, also Settings →
About "Check for updates" and the daily notification). It installs the
`-github-release.apk` asset, never a debug one. So a release must have the
APK attached and be marked latest (not a pre-release), or nobody is
offered it. Debug builds (`.debug`) never offer releases: they update from
PR builds.

## Check a release

```
gh release view vX.Y.Z -R lucas-romanenko/jellyfin-tentacle-androidtv
```

It lists `tentacle-androidtv-vX.Y.Z-github-release.apk` and
`tentacle-androidtv-github-release.apk`, and the App / Release run
succeeded.
