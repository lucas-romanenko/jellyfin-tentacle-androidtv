package org.jellyfin.androidtv.ui.composable

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

/**
 * Asks for focus until [isFocused] has held for [settleFrames] frames in a row, for at most
 * [maxFrames] frames. One requestFocus isn't enough when a screen opens or comes back from the back
 * stack: while its ComposeView doesn't hold Android focus yet, the request can end on the screen's
 * first focusable (a toolbar button) instead, and the view system can restore focus over it a frame
 * later. Asking again only while the target isn't focused leaves a press the user already made alone
 * once it has settled.
 */
suspend fun FocusRequester.requestFocusSettled(
	isFocused: () -> Boolean,
	maxFrames: Int = 30,
	settleFrames: Int = 3,
) {
	var settled = 0
	repeat(maxFrames) {
		if (isFocused()) {
			if (++settled >= settleFrames) return
		} else {
			settled = 0
			try {
				requestFocus()
			} catch (_: IllegalStateException) {
				// Not attached yet (the target isn't composed this frame): try again next frame.
			}
		}
		withFrameNanos { }
	}
}
