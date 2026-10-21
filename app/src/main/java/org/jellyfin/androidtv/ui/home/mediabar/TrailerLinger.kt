package org.jellyfin.androidtv.ui.home.mediabar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Keeps the hero trailer's player for a moment after the trailer stops being wanted, so it is
 * not torn down in the frame focus moves from the hero to the first row (Lucas 2026-10-21: "the
 * worst lag I saw was going from hero section to first row").
 *
 * Tearing it down at once blocked the main thread right as the rows start scrolling: hiding the
 * trailer view destroys its SurfaceView surface while ExoPlayer is still decoding (the detach
 * waits for the playback thread), and ExoPlayer.release() waits for the hardware decoder to be
 * released. Instead the player is hidden and stopped at once (stop() frees the decoder on
 * ExoPlayer's own thread) and the view goes and the player is released [DELAY_MS] later,
 * when both are cheap.
 */
class TrailerLinger {
	/** The trailer the view shows: the wanted one, or the last one while it lingers. */
	var shown by mutableStateOf<TrailerPreviewInfo?>(null)
		private set

	/** True from the moment the trailer is no longer wanted until [finish]. */
	var lingering by mutableStateOf(false)
		private set

	/** Bumped when a trailer is wanted again while the old one lingers: the stopped player can't be reused. */
	var epoch by mutableIntStateOf(0)
		private set

	/** The trailer that should play now (null: none). */
	fun update(wanted: TrailerPreviewInfo?) {
		if (wanted != null) {
			if (lingering) {
				lingering = false
				epoch++
			}
			shown = wanted
		} else if (shown != null && !lingering) {
			lingering = true
		}
	}

	/** The linger time is over: drop the old trailer (its view goes and its player is released). */
	fun finish() {
		if (!lingering) return
		lingering = false
		shown = null
	}

	companion object {
		/** Long enough for the row scroll to finish and ExoPlayer to free the decoder (ms). */
		const val DELAY_MS = 600L
	}
}

/** The trailer a Buffering or Playing state wants on screen (null: none, or nothing to play yet). */
fun TrailerPreviewState.playableInfo(): TrailerPreviewInfo? = when (this) {
	is TrailerPreviewState.Buffering -> info
	is TrailerPreviewState.Playing -> info
	else -> null
}?.takeIf { it.streamInfo != null }
