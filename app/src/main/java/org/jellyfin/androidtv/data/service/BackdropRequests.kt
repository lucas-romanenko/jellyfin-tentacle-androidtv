package org.jellyfin.androidtv.data.service

/**
 * Which background request is the latest, so only its picture reaches the screen (#14).
 *
 * Every request (a focused card, a details screen, a library tile, a clear) takes a ticket from
 * [start]; its load may publish only while that ticket is still the latest ([publish]). A slow
 * load for a card the focus already left (a decode and blur on the Sony takes a while) used to
 * land after the newer one and showed that other title behind the focused card.
 *
 * [key] says what a request shows (its image urls and blur, a library, null for none): asking
 * again for what is already on screen changes nothing ([alreadyShown]), and a picture of an
 * earlier request still on screen while the latest one loads is faded out after a grace
 * ([fadesOut]) instead of staying behind the wrong title. Main thread only.
 */
class BackdropRequests {
	private var latest = 0L
	private var latestKey: Any? = null

	/** What the picture on screen is for (null: none). */
	var shownKey: Any? = null
		private set

	/** True when [key] is on screen already: no load needed. */
	fun alreadyShown(key: Any?): Boolean = key != null && key == shownKey

	/** True when the latest request is for [key]. */
	fun isLatestKey(key: Any?): Boolean = key == latestKey

	/** A new request for [key]: every older one is out of date from now on. Returns its ticket. */
	fun start(key: Any?): Long {
		latest++
		latestKey = key
		return latest
	}

	/** True while [ticket] is the latest request. */
	fun isLatest(ticket: Long): Boolean = ticket == latest

	/**
	 * A load finished a picture for [ticket]: true if it may go on screen (it is the latest
	 * request), and then that request's key is what is shown.
	 */
	fun publish(ticket: Long): Boolean {
		if (ticket != latest) return false
		shownKey = latestKey
		return true
	}

	/** The latest request's load found no picture: nothing is shown. True if [ticket] is the latest. */
	fun publishNothing(ticket: Long): Boolean {
		if (ticket != latest) return false
		shownKey = null
		return true
	}

	/**
	 * The grace for [ticket] ran out: true if the screen still shows an earlier request's
	 * picture, which should go (the caller clears it); then nothing is shown.
	 */
	fun fadesOut(ticket: Long): Boolean {
		if (ticket != latest || shownKey == null || shownKey == latestKey) return false
		shownKey = null
		return true
	}
}
