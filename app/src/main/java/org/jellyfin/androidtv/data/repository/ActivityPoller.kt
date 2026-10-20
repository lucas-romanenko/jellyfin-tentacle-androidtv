package org.jellyfin.androidtv.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.androidtv.util.pollDelay

/**
 * Tentacle's download activity, polled once for the whole app (M9). Every navbar polled it
 * for its badge (one poller per screen), and the Activity tab polled it again every 3 s on
 * top of its own navbar. Screens now collect [activity]: it polls while anyone collects,
 * every 3 s while the Activity tab is open ([watchClosely]), else every 10 s while something
 * downloads and 30 s when idle, backing off while the server doesn't answer.
 */
class ActivityPoller(private val tentacleRepository: TentacleRepository) {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val closeWatchers = MutableStateFlow(0)
	// Bumped by pollNow(); a bump during a request is seen once it is done.
	private val pokes = MutableStateFlow(0)

	/**
	 * Each poll's answer (null: no answer). Nothing is replayed (a profile switch must not
	 * show the previous profile's downloads); opening the Activity tab polls at once.
	 */
	val activity: SharedFlow<ActivityResponse?> = flow {
		var failures = 0
		while (true) {
			val poked = pokes.value
			val response = tentacleRepository.getActivity()
			emit(response)
			failures = if (response != null && response.error.isNullOrBlank()) 0 else failures + 1
			val close = closeWatchers.value > 0
			val wait = pollDelay(
				baseMs = activityPollMs(close, tentacleRepository.activityDownloadCount.value),
				consecutiveFailures = failures,
				maxMs = if (close) CLOSE_MAX_MS else IDLE_MAX_MS,
			)
			// Asked to poll now, or the Activity tab just opened: don't wait out the interval.
			withTimeoutOrNull(wait) {
				merge(pokes.filter { it != poked }.map { }, closeWatchers.filter { it > 0 && !close }.map { }).first()
			}
		}
	}.shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000))

	/** While this runs (the Activity tab on screen), poll every 3 s. */
	suspend fun watchClosely(): Nothing {
		closeWatchers.update { it + 1 }
		try {
			awaitCancellation()
		} finally {
			closeWatchers.update { it - 1 }
		}
	}

	/** Poll now (something was added), if anyone is collecting. */
	fun pollNow() {
		pokes.update { it + 1 }
	}

	companion object {
		const val CLOSE_MS = 3_000L
		const val CLOSE_MAX_MS = 60_000L
		const val IDLE_MAX_MS = 300_000L
	}
}

/** The healthy interval: 3 s on the Activity tab, 10 s while downloading, else 30 s. */
internal fun activityPollMs(closely: Boolean, downloads: Int): Long = when {
	closely -> ActivityPoller.CLOSE_MS
	downloads > 0 -> 10_000L
	else -> 30_000L
}
