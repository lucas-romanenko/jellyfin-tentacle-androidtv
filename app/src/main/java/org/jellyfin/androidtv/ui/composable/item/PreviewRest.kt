package org.jellyfin.androidtv.ui.composable.item

import android.os.SystemClock
import kotlinx.coroutines.delay

/**
 * How long focus must rest on a card, with no key pressed, before its preview starts (H6).
 * At 0.5 s every pause in normal D-pad browsing built a video player and started a server
 * transcode (#47: one cut a running recording).
 */
internal const val PREVIEW_REST_MS = 2_000L

/** When a key was last pressed anywhere in the app (MainActivity.dispatchKeyEvent). */
object KeyActivity {
	@Volatile
	var lastKeyAt = 0L
		private set

	fun note() {
		lastKeyAt = SystemClock.uptimeMillis()
	}
}

/** How much longer to wait before [rest] ms have passed since the last key press (0: now). */
internal fun restRemaining(now: Long, lastKeyAt: Long, rest: Long): Long =
	(lastKeyAt + rest - now).coerceIn(0, rest)

/** Waits until focus has rested [rest] ms on this card and no key was pressed for as long. */
internal suspend fun awaitPreviewRest(rest: Long = PREVIEW_REST_MS) {
	delay(rest)
	while (true) {
		val remaining = restRemaining(SystemClock.uptimeMillis(), KeyActivity.lastKeyAt, rest)
		if (remaining == 0L) return
		delay(remaining)
	}
}
