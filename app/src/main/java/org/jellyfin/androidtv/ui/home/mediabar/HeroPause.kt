package org.jellyfin.androidtv.ui.home.mediabar

/**
 * Pauses the hero while the app is in the background (a8/04). The media bar view model is a
 * singleton that only stops when the hero loses focus, and leaving with the Home button, another
 * app or the system screensaver stops the activity without taking focus away: the carousel kept
 * advancing and looking up trailers with nobody watching.
 *
 * Only an activity stop pauses it. Navigating away detaches or removes the home fragment, which
 * disposes the hero's view and takes its focus anyway.
 */
class HeroPause {
	private var pausedWhileFocused = false

	/** The fragment stopped. True if the hero should be unfocused now. */
	fun onStop(heroFocused: Boolean, leavingByNavigation: Boolean): Boolean {
		if (leavingByNavigation || !heroFocused) return false
		pausedWhileFocused = true
		return true
	}

	/** The fragment resumed. True if the hero should get its focus back (this also restarts its trailer). */
	fun onResume(): Boolean {
		val resume = pausedWhileFocused
		pausedWhileFocused = false
		return resume
	}

	/** The view is gone: a later start belongs to a new view, whose focus decides. */
	fun onDestroyView() {
		pausedWhileFocused = false
	}
}
