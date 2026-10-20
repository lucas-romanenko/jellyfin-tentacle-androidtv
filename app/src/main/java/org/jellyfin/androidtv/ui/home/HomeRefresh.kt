package org.jellyfin.androidtv.ui.home

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.data.repository.SectionsAnswer
import timber.log.Timber
import kotlin.time.Duration

/**
 * One refresh of the Tentacle home (androidtv audit M3). Requests that arrive while one is
 * waiting are merged into it, so a burst of saves becomes one refresh.
 * - [stockRows]: reload the stock Jellyfin rows too (a sync or a library change, not a home save);
 * - [force]: refresh the Tentacle rows even if the home version did not move (a change
 *   without a version: an older plugin, a library scan);
 * - [settle]: wait this long before running, for a burst to end (and Jellyfin to index).
 */
data class HomeRefreshRequest(
	val stockRows: Boolean = false,
	val force: Boolean = false,
	val settle: Duration = Duration.ZERO,
) {
	fun merge(other: HomeRefreshRequest) = HomeRefreshRequest(
		stockRows = stockRows || other.stockRows,
		force = force || other.force,
		settle = maxOf(settle, other.settle),
	)
}

enum class HomeAnswerAction {
	/** Apply the sections (rows refreshed in place, or rebuilt where the layout changed). */
	APPLY,

	/** Home already shows this version. */
	SKIP,

	/** The home was switched off: show the stock rows. */
	SWITCH_TO_STOCK,

	/** No answer: keep what is shown. */
	KEEP,
}

/**
 * What a home refresh does with the sections answer. [shownVersion] is the home version on
 * screen (0: unknown); [showingTentacleHome] is false while the stock rows show.
 */
fun decideHomeRefresh(
	answer: SectionsAnswer,
	shownVersion: Long,
	showingTentacleHome: Boolean,
	force: Boolean,
): HomeAnswerAction = when (answer) {
	SectionsAnswer.Failed -> HomeAnswerAction.KEEP
	// Only a plugin that sends versions says "off" for sure; an older one is ignored, as before.
	is SectionsAnswer.Off -> if (showingTentacleHome && answer.homeVersion > 0) HomeAnswerAction.SWITCH_TO_STOCK else HomeAnswerAction.SKIP
	is SectionsAnswer.On -> when {
		force || !showingTentacleHome -> HomeAnswerAction.APPLY
		// An older plugin has no version: no way to tell, so refresh as before.
		answer.response.homeVersion == 0L -> HomeAnswerAction.APPLY
		answer.response.homeVersion == shownVersion -> HomeAnswerAction.SKIP
		else -> HomeAnswerAction.APPLY
	}
}

/**
 * Runs [action] for one request at a time; requests that come in meanwhile are merged into
 * one that runs next, so the newest always runs and an older one never lands last. Nothing
 * runs before [ready] (the first load) completes: what was asked meanwhile runs once after.
 */
class SerialRefresher<T : Any>(
	scope: CoroutineScope,
	ready: Job,
	private val merge: (T, T) -> T,
	private val settle: (T) -> Duration,
	private val action: suspend (T) -> Unit,
) {
	private val lock = Any()
	private var pending: T? = null
	private val wake = Channel<Unit>(Channel.CONFLATED)

	init {
		scope.launch {
			ready.join()
			for (signal in wake) {
				val first = synchronized(lock) { pending } ?: continue
				delay(settle(first))
				val request = synchronized(lock) { pending.also { pending = null } } ?: continue
				try {
					action(request)
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					Timber.w(e, "Home refresh failed")
				}
			}
		}
	}

	fun request(request: T) {
		synchronized(lock) { pending = pending?.let { merge(it, request) } ?: request }
		wake.trySend(Unit)
	}
}

/** How many content rows below the hero the loading screen waits for (H4). */
internal const val FIRST_SCREEN_ROWS = 2

/**
 * The Tentacle rows the loading screen waits for at the first load: those among the first
 * [FIRST_SCREEN_ROWS] content rows (the hero left out), by playlist id. [rowPlaylists] lists the
 * content rows in order, a Tentacle row by its playlist id, any other row as null. The rows
 * further down fill in after the loading screen has gone.
 */
internal fun firstScreenRows(rowPlaylists: List<String?>): Set<String> =
	rowPlaylists.take(FIRST_SCREEN_ROWS).filterNotNull().toSet()
