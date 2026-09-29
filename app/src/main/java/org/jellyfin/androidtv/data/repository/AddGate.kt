package org.jellyfin.androidtv.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Titles with a Radarr/Sonarr add in flight from this app (a8/05). An add takes 40-130 s, and
 * closing the dialog doesn't stop it: reopening the title offered Add again, and a second add of
 * the same title reached Radarr while the first was still running. A key is held until its
 * request has really ended, so the dialog can show "Adding..." again, and a second add is
 * refused instead of joined: a different pick (episodes, quality profile) must not be merged
 * into the first request.
 */
class AddGate {
	private val _inFlight = MutableStateFlow<Set<String>>(emptySet())
	val inFlight: StateFlow<Set<String>> = _inFlight.asStateFlow()

	/** True if [key] was free and is now held by the caller, who must [release] it. */
	fun tryAcquire(key: String): Boolean {
		var acquired = false
		_inFlight.update { keys ->
			acquired = key !in keys
			if (acquired) keys + key else keys
		}
		return acquired
	}

	fun release(key: String) = _inFlight.update { it - key }

	companion object {
		/**
		 * One title for one user on one server: another profile, or the same title on another
		 * server, is a separate add.
		 */
		fun key(server: String, userId: String, arr: String, tmdbId: Int, tvdbId: Int = 0): String =
			"$server|$userId|$arr|" + if (tmdbId > 0) "tmdb:$tmdbId" else "tvdb:$tvdbId"
	}
}

/** Runs [block] holding [key], or answers [busy] if an add of that title is already in flight. */
inline fun <T> AddGate.withKey(key: String?, busy: () -> T, block: () -> T): T {
	if (key == null) return block()
	if (!tryAcquire(key)) return busy()
	try {
		return block()
	} finally {
		release(key)
	}
}
