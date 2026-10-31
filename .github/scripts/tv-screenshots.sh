#!/usr/bin/env bash
# Runs ManageEpisodesScreenshotTest on the booted emulator and copies its PNGs to build/tv-screens.
# Called by .github/workflows/app-tv-screenshots.yaml.
set -euo pipefail

app=org.jellyfin.tentacle.debug
out=build/tv-screens
mkdir -p "$out"

adb shell wm size
adb install -r -t "$(ls app/build/outputs/apk/github/debug/*.apk | head -n 1)"
adb install -r -t "$(ls app/build/outputs/apk/androidTest/github/debug/*.apk | head -n 1)"

adb shell am instrument -w \
	-e class org.jellyfin.androidtv.ui.discover.ManageEpisodesScreenshotTest \
	"$app.test/androidx.test.runner.AndroidJUnitRunner" | tee "$out/instrument.txt"
# am instrument exits 0 on a failed test: its summary says how it went
grep -q '^OK (' "$out/instrument.txt"

for name in $(adb shell run-as "$app" ls files/screens | tr -d '\r'); do
	adb exec-out run-as "$app" cat "files/screens/$name" > "$out/$name"
done
ls -l "$out"
