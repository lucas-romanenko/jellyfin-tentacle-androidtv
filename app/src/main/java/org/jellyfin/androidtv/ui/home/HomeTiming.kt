package org.jellyfin.androidtv.ui.home

import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * Start-up and Home load milestones in logcat (tag `HomeTiming`), in ms since the app's
 * process started: a cold start is measured from them (`adb logcat -s HomeTiming`).
 * Logged with [Log] directly, not Timber, so release builds print them too (their Timber
 * tree drops info lines): a cold start is measured on the release build.
 */
internal object HomeTiming {
	fun mark(what: String) {
		val sinceStart = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			"+${SystemClock.uptimeMillis() - Process.getStartUptimeMillis()} ms"
		} else "(uptime ${SystemClock.uptimeMillis()} ms)"
		Log.i("HomeTiming", "$what $sinceStart")
	}
}
