package org.jellyfin.androidtv.ui.browsing.v2

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.RecordingStatus
import timber.log.Timber
import java.time.LocalDateTime
import java.util.UUID

/** What the Recording now row says under a card: since when, on which channel (from its timer). */
/** A recording in progress: since when, and its channel ([channelId] null when no timer matched). */
data class RecordingNowInfo(val since: LocalDateTime?, val channelName: String?, val channelId: UUID? = null)

/** What OK does on a recording card. */
enum class RecordingOk {
	/** Recording now: ask how to watch it ([recordingNowChoices]). */
	CHOOSE,

	/** Recorded: as before (the item's own launch). */
	OPEN,
}

/** OK on a recording card: one still recording asks first (#239), a finished one opens as before. */
fun recordingOk(now: RecordingNowInfo?): RecordingOk = if (now == null) RecordingOk.OPEN else RecordingOk.CHOOSE

/** The ways to watch a recording that is still recording. */
enum class RecordingNowChoice {
	/** The channel live, joining the stream the recording already reads (no new provider connection). */
	WATCH_LIVE,

	/** Its file from the saved position; offered when there is one. */
	RESUME,

	/** Its file from 0:00, seekable in the part already recorded, growing as it records. */
	FROM_START,
}

/**
 * What a recording still recording offers (Lucas, 30 Oct: Watch live / Play from start), in this
 * order: live first (today's OK), the saved position, the start. Live needs the channel (from
 * its timer); [resumeTicks] is the saved position, if any.
 */
fun recordingNowChoices(channelKnown: Boolean, resumeTicks: Long?): List<RecordingNowChoice> = buildList {
	if (channelKnown) add(RecordingNowChoice.WATCH_LIVE)
	if (resumeTicks != null && resumeTicks > 0) add(RecordingNowChoice.RESUME)
	add(RecordingNowChoice.FROM_START)
}

data class RecordingsBrowseUiState(
	val isLoading: Boolean = true,
	val groups: RecordedGroups = RecordedGroups(),
	val recordingNow: Map<UUID, RecordingNowInfo> = emptyMap(),
	/** The show page open in place of the rows (its title), or null. */
	val openShow: String? = null,
)

class RecordingsBrowseViewModel(
	val api: ApiClient,
) : ViewModel() {

	private val _uiState = MutableStateFlow(RecordingsBrowseUiState())
	val uiState: StateFlow<RecordingsBrowseUiState> = _uiState.asStateFlow()

	// The focused card, apart from uiState: a D-pad press recomposes only the header HUD.
	private val _focusedItem = MutableStateFlow<RecordingFocus?>(null)
	val focusedItem: StateFlow<RecordingFocus?> = _focusedItem.asStateFlow()

	fun initialize() {
		_uiState.value = RecordingsBrowseUiState(isLoading = true)
		_focusedItem.value = null
		loadAll()
	}

	/** Read again without the loading state (a recording changed): the cards stay where they are. */
	fun refresh() = loadAll()

	fun setFocused(focus: RecordingFocus) {
		_focusedItem.value = focus
	}

	fun openShow(title: String?) {
		_uiState.update { it.copy(openShow = title) }
	}

	private fun loadAll() {
		viewModelScope.launch {
			try {
				val itemsJob = async(Dispatchers.IO) { loadRecordings() }
				val inProgressJob = async(Dispatchers.IO) { loadInProgressIds() }
				val items = itemsJob.await()
				val inProgress = inProgressJob.await()
				val nowInfo = if (inProgress.isEmpty()) emptyMap() else loadRecordingNowInfo(items.filter { it.id in inProgress })
				val groups = withContext(Dispatchers.Default) { groupRecordings(items, inProgress, LocalDateTime.now()) }
				_uiState.update { state ->
					// A show page whose last recording went away closes.
					val open = state.openShow?.takeIf { title -> groups.shows.any { it.title == title } }
					state.copy(isLoading = false, groups = groups, recordingNow = nowInfo, openShow = open)
				}
			} catch (err: ApiClientException) {
				Timber.e(err, "Failed to load recordings")
				_uiState.update { it.copy(isLoading = false) }
			}
		}
	}

	/** Every recording in one paged call with only the fields the rows use. */
	private suspend fun loadRecordings(): List<BaseItemDto> {
		val all = ArrayList<BaseItemDto>()
		var start = 0
		while (start < MAX_RECORDINGS) {
			val page = api.liveTvApi.getRecordings(
				startIndex = start,
				limit = PAGE_SIZE,
				fields = RECORDING_FIELDS,
				enableImages = true,
				imageTypeLimit = 1,
				enableImageTypes = listOf(ImageType.THUMB, ImageType.PRIMARY),
				enableUserData = true,
				enableTotalRecordCount = start == 0,
			).content
			all += page.items
			if (page.items.size < PAGE_SIZE) break
			start += PAGE_SIZE
		}
		return all
	}

	private suspend fun loadInProgressIds(): Set<UUID> = try {
		api.liveTvApi.getRecordings(isInProgress = true, enableImages = false, enableUserData = false, enableTotalRecordCount = false)
			.content.items.mapTo(HashSet()) { it.id }
	} catch (err: ApiClientException) {
		Timber.w(err, "Failed to read recordings in progress")
		emptySet()
	}

	/** "Since 7:00 PM · TSN 2" from the running timers, matched by name (recordings carry no channel). */
	private suspend fun loadRecordingNowInfo(items: List<BaseItemDto>): Map<UUID, RecordingNowInfo> = try {
		val timers = withContext(Dispatchers.IO) { api.liveTvApi.getTimers(isActive = true).content.items }
			.filter { it.status == RecordingStatus.IN_PROGRESS || it.status == null }
		items.associate { item ->
			val timer = timers.firstOrNull { it.name.equals(item.name, ignoreCase = true) }
				?: timers.singleOrNull()?.takeIf { items.size == 1 }
			item.id to RecordingNowInfo(timer?.startDate ?: item.dateCreated, timer?.channelName ?: item.channelName, timer?.channelId ?: item.channelId)
		}
	} catch (err: ApiClientException) {
		Timber.w(err, "Failed to read running timers")
		emptyMap()
	}

	/**
	 * [item] read again for its menu: LiveTv/Recordings leaves out CanDelete even when asked, so
	 * whether this person may delete it comes from the item itself. The listed copy if that fails.
	 */
	suspend fun forMenu(item: BaseItemDto): BaseItemDto = try {
		withContext(Dispatchers.IO) { api.userLibraryApi.getItem(itemId = item.id).content }
	} catch (err: ApiClientException) {
		Timber.w(err, "Failed to read ${item.id} for its menu")
		item
	}

	/** Mark watched or not watched for this person; the rows read again. */
	fun setPlayed(item: BaseItemDto, played: Boolean, onDone: (Boolean) -> Unit) {
		viewModelScope.launch {
			val ok = try {
				withContext(Dispatchers.IO) {
					if (played) api.playStateApi.markPlayedItem(itemId = item.id)
					else api.playStateApi.markUnplayedItem(itemId = item.id)
				}
				true
			} catch (err: ApiClientException) {
				Timber.e(err, "Failed to mark ${item.id} played=$played")
				false
			}
			onDone(ok)
			if (ok) refresh()
		}
	}

	/**
	 * Deletes one recording for the household. Only offered where Jellyfin's CanDelete says this person
	 * may, and never for a TMDB title, whose delete must go through Tentacle (#69).
	 */
	fun delete(item: BaseItemDto, onDone: (Boolean) -> Unit) {
		if (!canOfferRecordingDelete(item)) {
			onDone(false)
			return
		}
		viewModelScope.launch {
			val ok = try {
				withContext(Dispatchers.IO) { api.libraryApi.deleteItem(itemId = item.id) }
				true
			} catch (err: ApiClientException) {
				Timber.e(err, "Failed to delete recording ${item.id}")
				false
			}
			onDone(ok)
			if (ok) refresh()
		}
	}

	companion object {
		private const val PAGE_SIZE = 200
		private const val MAX_RECORDINGS = 5000
		private val RECORDING_FIELDS = listOf(
			ItemFields.DATE_CREATED,
			ItemFields.PATH,
			ItemFields.GENRES,
		)
	}
}

/** The focused card for the header: one recording, or a show card (its recordings). */
sealed interface RecordingFocus {
	data class Recording(val item: BaseItemDto, val recordingNow: RecordingNowInfo?) : RecordingFocus
	data class Show(val show: RecordedShow) : RecordingFocus
}
