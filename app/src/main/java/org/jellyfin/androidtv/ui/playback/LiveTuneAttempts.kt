package org.jellyfin.androidtv.ui.playback

/**
 * Numbers the Live TV tunes of one player, so only the newest one's PlaybackInfo answer may
 * start (#176). A second CH+/CH- while the first tune is still opening starts another request;
 * both answers come back, possibly out of order, and the older one must not play (or retry on
 * error) over the newer channel. The older stream is not closed here: a stream the player never
 * reported is released on the server side (Tentacle plugin), never with LiveStreams/Close.
 */
class LiveTuneAttempts {
	private var latest = 0

	/** Starts a tune; returns its number. */
	fun start(): Int = ++latest

	/** Whether [attempt] is still the newest tune. */
	fun isCurrent(attempt: Int): Boolean = attempt == latest
}
