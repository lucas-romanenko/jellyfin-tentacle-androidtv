package org.jellyfin.androidtv.ui

import androidx.media3.common.Player

/**
 * Keeps the TV awake (no screensaver, no screen off) while a player outside the normal player
 * shows video, as the normal player does in VideoManager's onIsPlayingChanged: the lock is held
 * only while the player really plays, so paused, stopped, an error or the player let go hands the
 * screensaver back. Attach it as the player's listener and [release] it when the player goes.
 *
 * @param addLock takes the lock and returns the function that gives it back
 *   ([InteractionTrackerViewModel.addLifecycleLock]).
 */
class KeepAwakeWhilePlaying(private val addLock: () -> () -> Unit) : Player.Listener {
	private var lock: (() -> Unit)? = null

	/** Whether the screen is being kept awake now. */
	val held get() = lock != null

	override fun onIsPlayingChanged(isPlaying: Boolean) = set(isPlaying)

	fun set(playing: Boolean) {
		if (playing && lock == null) {
			lock = addLock()
		} else if (!playing) {
			lock?.invoke()
			lock = null
		}
	}

	fun release() = set(false)
}
