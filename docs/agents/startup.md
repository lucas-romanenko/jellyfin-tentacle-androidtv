# Start-up: measuring and the start-up profile

How long a cold start takes (process start to a usable Home) and what makes it
slow. Measured on a Sony Android TV (Android 9, 2 GB RAM): the numbers and the
history are in the maintainer's notes; this page is the method.

## What decides the time

- **Compile state, most of all.** After an install or update Android 9 runs the
  app interpreted and JIT-compiled until its idle-time dexopt (overnight on a
  TV) compiles it from a profile. On the Sony the same release APK reached Home
  in about 11 s right after install and in about 5 s once compiled. So always
  say which state a number is for.
- **Main-thread work** on the way to Home: the crash reporter (ACRA) set-up,
  Koin, the start-up screen's first frames, Home's first layout (the navbar's
  first composition is the largest part), then the loading screen holds until
  the hero's first backdrop is drawn.
- **The debug build is no guide:** it is not minified and is debuggable (no
  ahead-of-time code), several times slower. Measure the bench build.

## The bench build

`bench` (app/build.gradle.kts) is the release build as a side-by-side app,
"Tentacle Bench" (`org.jellyfin.tentacle.bench`), signed with the debug key.
CI builds `assembleGithubBench` for pull requests; the APK and its R8 mapping
(`bench-mapping.txt`) are in the run's `build-artifacts`. Each CI run has its
own debug key, so a new bench build installs only after uninstalling the old
one, and then needs a sign-in (Quick Connect). `-Ptentacle.benchDebuggable=true`
makes it debuggable for a method trace; don't time that one.

## Timing marks

`HomeTiming.mark()` logs milestones in ms since process start with
`android.util.Log` (tag `HomeTiming`), in every build type:

```
adb logcat -s HomeTiming
```

From the crash reporter set-up and Koin through the session restore,
StartupActivity, MainActivity and Home's first layout to the hero
(`hero answered`, `hero first slide cached`, `hero backdrop drawn`),
`rows and hero ready` and `Home usable` (the loading screen gone).

A cold start: `am force-stop`, `logcat -c`, `am start -n
<package>/org.jellyfin.androidtv.ui.startup.StartupActivity`, read the marks.
To compare compiled state: `cmd package compile -m speed-profile -f <package>`
(what the idle-time dexopt does); `-m verify -f` puts it back to as-installed.

## The start-up profile

`app/src/main/baseline-prof.txt` lists the classes the app runs while starting
and showing Home; AGP compiles it into the APK (`assets/dexopt/baseline.prof`)
and androidx.profileinstaller hands it to Android on first launch, so the
idle-time dexopt compiles those classes even before the app has been used much.

To regenerate it (after large changes to start-up or Home):

1. Install a bench build, sign in, do a dozen cold starts to Home.
2. `adb shell cmd package dump-profiles org.jellyfin.tentacle.bench`, then
   `adb pull /data/misc/profman/org.jellyfin.tentacle.bench-primary.prof.txt`.
3. `python3 scripts/baseline-profile.py <that file> bench-mapping.txt
   app/src/main/baseline-prof.txt` (the same CI run's mapping: the profile
   has R8's names).
