package org.jellyfin.androidtv.ui.livetv

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import org.jellyfin.sdk.model.api.TimerInfoDto
import org.jellyfin.sdk.model.api.RecordingStatus
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.androidtv.data.eventhandling.RecordingChanges
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.data.repository.HomeVersionTracker
import org.jellyfin.androidtv.preference.LiveTvPreferences
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.UserDataChangedMessage
import org.jellyfin.sdk.model.api.SeriesTimerCancelledMessage
import org.jellyfin.sdk.model.api.SeriesTimerCreatedMessage
import org.jellyfin.sdk.model.api.TimerCancelledMessage
import org.jellyfin.sdk.model.api.TimerCreatedMessage
import org.koin.android.ext.android.inject
import org.tentacle.server.core.model.ServerType
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The guide follows changes while it is on screen, without a reload: no flash, focus and scroll
 * stay (the rows change in place, [LiveTvGuideFragment.applyLiveChannels]).
 *
 * - UserDataChanged (Jellyfin sends it about a second after any favourite change, whoever made it:
 *   Tentacle's server, the web, another device, this guide): the stars change; in Favorites the
 *   unstarred channel's row goes, and a starred channel comes in (the list is read again for its
 *   name, logo and place).
 * - LibraryChanged (SocketHandler, [HomeVersionTracker.changes]; Tentacle sends one marked
 *   "tentacle-livetv:<version>" after every Live TV setup run, Jellyfin its own): after 1.5 s of
 *   quiet the channel list is read again (one request) and only what differs changes.
 * - The socket came back ([HomeVersionTracker.reconnects]) or the guide came back to the front
 *   ([relist] from onResume): read once, as messages may have been missed meanwhile.
 *
 * Events are handled one at a time in order, so two changes never edit the rows at once. Nothing
 * is heard while the guide isn't started.
 */
class GuideLiveUpdates(private val fragment: LiveTvGuideFragment) {
	private val api by fragment.inject<ApiClient>()
	private val liveTvPreferences by fragment.inject<LiveTvPreferences>()
	private val sessionRepository by fragment.inject<SessionRepository>()
	private val serverRepository by fragment.inject<ServerRepository>()
	private val homeVersionTracker by fragment.inject<HomeVersionTracker>()

	private sealed interface Event {
		data class UserData(val userId: String, val items: List<GuideUserData>) : Event
		/** A star changed in this guide: the Favorites view drops an unstarred row now. */
		data object Recompute : Event
		/** Read the channel list again and apply what differs. */
		data object Relist : Event
	}

	private val events = Channel<Event>(Channel.UNLIMITED)

	/** Read the channel list again (back in front, socket back). */
	fun relist() {
		events.trySend(Event.Relist)
	}

	/** A star changed in the guide itself (long press on a channel, the popup's heart). */
	fun recompute() {
		events.trySend(Event.Recompute)
	}

	fun start() {
		fragment.lifecycleScope.launch {
			fragment.repeatOnLifecycle(Lifecycle.State.STARTED) {
				launch { handleEvents() }

				if (serverRepository.currentServer.value?.serverType != ServerType.EMBY) launch {
					// The SDK's flow can end silently after a Jellyfin restart: subscribe again.
					while (isActive) {
						try {
							api.webSocket.subscribe<UserDataChangedMessage>().collect { message ->
								val data = message.data ?: return@collect
								events.trySend(
									Event.UserData(
										userId = data.userId.toString(),
										items = data.userDataList.map { GuideUserData(it.itemId.toString(), it.isFavorite) },
									)
								)
							}
						} catch (e: CancellationException) {
							throw e
						} catch (e: Exception) {
							Timber.w(e, "Guide: UserDataChanged subscription error")
						}
						delay(5.seconds)
					}
				}

				launch {
					var settle: Job? = null
					homeVersionTracker.changes.collect { change ->
						if (change.liveTvVersion != null) Timber.i("Guide: Live TV setup ran (version ${change.liveTvVersion})")
						settle?.cancel()
						settle = launch {
							delay(LIBRARY_SETTLE)
							events.trySend(Event.Relist)
						}
					}
				}

				launch {
					homeVersionTracker.reconnects.collect { events.trySend(Event.Relist) }
				}

				// A recording set or cancelled anywhere (this TV, the web, Tentacle's teams and
				// rules): the record icons on the programmes change in place. Heard three ways:
				// Jellyfin's timer messages on this guide's own subscription (like the stars
				// above), the app-wide RecordingChanges (Tentacle's "recordings" push), and a
				// re-read every minute while the guide is on screen.
				val recordChanges = Channel<String>(Channel.CONFLATED)
				if (serverRepository.currentServer.value?.serverType != ServerType.EMBY) {
					launch { resubscribe { api.webSocket.subscribe<TimerCreatedMessage>().collect { recordChanges.trySend("TimerCreated") } } }
					launch { resubscribe { api.webSocket.subscribe<TimerCancelledMessage>().collect { recordChanges.trySend("TimerCancelled") } } }
					launch { resubscribe { api.webSocket.subscribe<SeriesTimerCreatedMessage>().collect { recordChanges.trySend("SeriesTimerCreated") } } }
					launch { resubscribe { api.webSocket.subscribe<SeriesTimerCancelledMessage>().collect { recordChanges.trySend("SeriesTimerCancelled") } } }
				}
				launch { RecordingChanges.changes.collect { recordChanges.trySend("Tentacle") } }
				launch {
					while (isActive) {
						delay(RECORD_REREAD)
						recordChanges.trySend("minute")
					}
				}
				launch {
					for (why in recordChanges) {
						delay(RECORD_SETTLE)
						val timers = try {
							withContext(Dispatchers.IO) { api.liveTvApi.getTimers().content.items }
						} catch (e: CancellationException) {
							throw e
						} catch (e: Exception) {
							Timber.w(e, "Guide: recordings not read again")
							continue
						}
						val changed = fragment.applyRecordMarks(guideRecordMarks(timers))
						if (why != "minute" || changed > 0) {
							android.util.Log.i("GuideRecord", "$why: ${timers.size} timers, $changed programme(s) changed")
						}
					}
				}
			}
		}
	}

	private suspend fun handleEvents() {
		while (true) {
			val batch = mutableListOf(events.receive())
			while (true) batch += events.tryReceive().getOrNull() ?: break
			try {
				handle(batch)
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Timber.w(e, "Guide: live update failed")
			}
		}
	}

	private suspend fun handle(batch: List<Event>) {
		var relist = batch.any { it == Event.Relist }
		if (!fragment.isLiveReady()) {
			// A load is on its way: wait for it, then read again, as it may have asked before the change.
			withTimeoutOrNull(30.seconds) { while (!fragment.isLiveReady()) delay(300.milliseconds) } ?: return
			relist = true
		}
		val view = fragment.getGuideView() ?: return
		val generation = fragment.getLoadGeneration()
		val current = fragment.getLiveChannels() ?: return

		if (relist) return relist(view, generation)

		val me = sessionRepository.currentSession.value?.userId?.toString()
		val knownChannels = TvManager.getAllChannels()?.let { all -> all.mapTo(HashSet()) { it.id } }
		var target: List<BaseItemDto> = current
		for (event in batch) {
			when (event) {
				is Event.UserData -> {
					val update = guideUserDataUpdate(view, target, me, event.userId, event.items, knownChannels)
					if (update.relist) return relist(view, generation)
					update.channels?.let { target = it }
					// The full list's copies too (the popup's heart reads them), when the Favorites view shows
					if (view == GuideView.FAVORITES) TvManager.getAllChannels()?.let { all ->
						for ((id, favorite) in guideFavoriteFlips(all, me, event.userId, event.items)) {
							all.firstOrNull { it.id == id }?.let { TvManager.updateChannel(it.withFavorite(favorite)) }
						}
					}
				}

				Event.Recompute -> if (view == GuideView.FAVORITES) target = favoritesOnly(target)
				Event.Relist -> Unit
			}
		}
		if (target !== current) apply(target, allCount = null, generation)
	}

	private suspend fun relist(view: GuideView, generation: Int) {
		val target: List<BaseItemDto>
		var allCount: Int? = null
		try {
			if (view == GuideView.FAVORITES) {
				target = fetchFavoriteGuideChannels(api, liveTvPreferences)
				allCount = runCatching { fetchGuideChannelCount(api) }.getOrNullUnlessCancelled()
			} else {
				target = fetchAllGuideChannels(api, liveTvPreferences)
			}
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Timber.w(e, "Guide: unable to read the channels again")
			return
		}
		if (fragment.getGuideView() != view) return
		apply(target, allCount, generation)
	}

	/** Shows [target] in place of the guide's list, changing only the rows that differ. */
	private suspend fun apply(target: List<BaseItemDto>, allCount: Int?, generation: Int) {
		// A load started meanwhile (Favorites / All, paging, settings): it shows the server's list itself
		if (fragment.getLoadGeneration() != generation || !fragment.isLiveReady()) return
		val current = fragment.getLiveChannels() ?: return
		val diff = diffGuideChannels(current, target)
		if (diff.isEmpty) {
			fragment.onLiveCounts(allCount)
			return
		}
		Timber.i("Guide: live change, +${diff.added.size} -${diff.removed.size} ~${diff.changed.size}${if (diff.reordered) " reordered" else ""}")

		val needed = fragment.rowsNeededFor(target)
		if (needed.isNotEmpty()) {
			val start = fragment.getCurrentLocalStartDate()
			val end = fragment.getCurrentLocalEndDate()
			val programs = runCatching {
				fetchGuidePrograms(api, needed, guideStartFor(start), end.minusSeconds(1))
			}.getOrNullUnlessCancelled().orEmpty()
			if (fragment.getLoadGeneration() != generation || !fragment.isLiveReady() || fragment.getLiveChannels() !== current) return
			TvManager.addPrograms(programs, needed, start)
		}
		fragment.applyLiveChannels(target, allCount)

		if (diff.lineupChanged && target.isNotEmpty()) {
			// The dates on offer stop where this view's guide data stops
			val ids = if (fragment.getGuideView() == GuideView.FAVORITES) target.map { it.id } else null
			val furthest = runCatching { fetchFurthestProgramEnd(api, ids) }.getOrNullUnlessCancelled()
			if (furthest != null && fragment.getLoadGeneration() == generation) fragment.onLiveGuideEnd(furthest)
		}
	}

	private companion object {
		val LIBRARY_SETTLE = 1500.milliseconds
	}
}

/** How long the guide waits after a recording change before it reads Jellyfin's timers. */
private val RECORD_SETTLE = 600.milliseconds

/** The guide re-reads its record icons this often while on screen, in case a message was missed. */
private val RECORD_REREAD = 60.seconds

/** Runs [subscription] again after it ends or fails (the SDK's flow can end after a Jellyfin restart). */
private suspend fun kotlinx.coroutines.CoroutineScope.resubscribe(subscription: suspend () -> Unit) {
	while (isActive) {
		try {
			subscription()
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			Timber.w(e, "Guide: recordings subscription error")
		}
		delay(5.seconds)
	}
}

/**
 * Programme id (no dashes, lower case) -> [timer id, series timer id] of the recordings that are
 * set or running, for the guide's record icons ([LiveTvGuideFragment.applyRecordMarks]).
 */
fun guideRecordMarks(timers: List<TimerInfoDto>): Map<String, Array<String?>> = recordMarks(timers.map {
	RecordTimer(it.id, it.programId, it.status == RecordingStatus.NEW || it.status == RecordingStatus.IN_PROGRESS, it.seriesTimerId)
})

/** One Jellyfin timer as the guide's record icons need it ([active]: set or recording now). */
data class RecordTimer(val id: String?, val programId: String?, val active: Boolean, val seriesTimerId: String?)

internal fun recordMarks(timers: List<RecordTimer>): Map<String, Array<String?>> {
	val out = HashMap<String, Array<String?>>()
	for (t in timers) {
		val programId = t.programId?.replace("-", "")?.lowercase() ?: continue
		if (!t.active) continue
		out[programId] = arrayOf(t.id, t.seriesTimerId)
	}
	return out
}
