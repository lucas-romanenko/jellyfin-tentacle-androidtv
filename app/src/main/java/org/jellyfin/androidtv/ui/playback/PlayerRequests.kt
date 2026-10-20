package org.jellyfin.androidtv.ui.playback

import kotlin.coroutines.cancellation.CancellationException

/**
 * For a player request's onFailure: a cancelled request is rethrown instead of handled as a
 * failure. The player's requests run in its lifecycleScope; when the player closes (Back
 * pressed while a channel, a timer or a cast list is still being asked for) the request is
 * cancelled, and runCatching hands that cancellation to onFailure, which then calls
 * requireContext() or closePlayer() on a destroyed fragment and the app closes (as in #124).
 */
internal fun Throwable.rethrowIfCancelled() {
	if (this is CancellationException) throw this
}
