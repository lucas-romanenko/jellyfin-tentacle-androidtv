package org.jellyfin.androidtv.ui.home

import android.os.Build
import android.os.Process
import android.os.SystemClock
import timber.log.Timber

/**
 * Home's load milestones in logcat (tag `HomeTiming`), in ms since the app's process
 * started: a cold start is measured from them (`adb logcat -s HomeTiming`).
 */
internal object HomeTiming {
	fun mark(what: String) {
		val sinceStart = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			"+${SystemClock.uptimeMillis() - Process.getStartUptimeMillis()} ms"
		} else "(uptime ${SystemClock.uptimeMillis()} ms)"
		Timber.tag("HomeTiming").i("%s %s", what, sinceStart)
	}
}
