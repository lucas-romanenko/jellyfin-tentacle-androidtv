package org.jellyfin.androidtv.ui.browsing.v2

import androidx.compose.foundation.lazy.LazyListState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.preference.LiveTvPreferences
import org.jellyfin.androidtv.ui.livetv.withoutChannelSeparators
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.api.client.extensions.personsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import timber.log.Timber

data class FavoritesBrowseUiState(
	val isLoading: Boolean = true,
	val rows: List<FavoriteRow> = emptyList(),
	/**
	 * The card to focus because the focused one just went (unstarred elsewhere): its neighbour,
	 * chosen when the rows were swapped, before Compose moves focus somewhere of its own.
	 */
	val refocus: String? = null,
)

class FavoritesBrowseViewModel(
	val api: ApiClient,
	private val liveTvPreferences: LiveTvPreferences,
) : ViewModel() {

	private val _uiState = MutableStateFlow(FavoritesBrowseUiState())
	val uiState: StateFlow<FavoritesBrowseUiState> = _uiState.asStateFlow()

	// Kept out of uiState: the focus moves with every D-pad press, and only the header shows it.
	private val _focusedItem = MutableStateFlow<BaseItemDto?>(null)
	val focusedItem: StateFlow<BaseItemDto?> = _focusedItem.asStateFlow()

	// The screen's place, kept while a details page or the player is on top (the fragment's view
	// goes, this ViewModel stays): the rows' scroll and the card to focus again.
	val listState = LazyListState()
	private val rowStates = HashMap<FavoriteRowKind, LazyListState>()
	fun rowState(kind: FavoriteRowKind): LazyListState = rowStates.getOrPut(kind) { LazyListState() }
	var lastFocusedId: String? = null
		private set
	var lastFocusedKind: FavoriteRowKind? = null
		private set
	var lastFocusedIndex = 0
		private set

	/** The ids shown as favourites, for [favoritesChanged]. */
	var shownIds: Set<String> = emptySet()
		private set

	private var loadJob: Job? = null
	private var started = false

	// The last good read of each source: a failed read keeps what is shown
	private var items = emptyList<BaseItemDto>()
	private var channels = emptyList<BaseItemDto>()
	private var people = emptyList<BaseItemDto>()

	/** The first load; afterwards (back from a details page) the rows are read again in place. */
	fun initialize() {
		started = true
		refresh()
	}

	fun setFocusedItem(item: BaseItemDto, kind: FavoriteRowKind, index: Int) {
		_focusedItem.value = item
		lastFocusedId = item.id.toString()
		lastFocusedKind = kind
		lastFocusedIndex = index
	}

	/**
	 * Reads every row again (three requests at once) and swaps them in: rows are keyed by item,
	 * so cards that stay keep their place and focus. A read in flight is replaced.
	 */
	fun refresh() {
		if (!started) return
		loadJob?.cancel()
		loadJob = viewModelScope.launch {
			val newItems = async { loadItems() }
			val newChannels = async { loadChannels() }
			val newPeople = async { loadPeople() }
			newItems.await()?.let { items = it }
			newChannels.await()?.let { channels = it }
			newPeople.await()?.let { people = it }
			val rows = favoriteRows(items, channels, people)
			val ids = favoriteIds(rows)
			val focused = lastFocusedId
			val refocus = if (focused != null && focused in shownIds && focused !in ids) {
				favoriteFocusTarget(rows, focused, lastFocusedKind, lastFocusedIndex)
			} else null
			shownIds = ids
			_uiState.value = FavoritesBrowseUiState(isLoading = false, rows = rows, refocus = refocus)
		}
	}

	/** Every favourite item but channels and people, in one request. */
	private suspend fun loadItems(): List<BaseItemDto>? = try {
		withContext(Dispatchers.IO) {
			api.itemsApi.getItems(
				sortBy = setOf(ItemSortBy.SORT_NAME),
				filters = setOf(ItemFilter.IS_FAVORITE),
				includeItemTypes = setOf(
					BaseItemKind.MOVIE,
					BaseItemKind.SERIES,
					BaseItemKind.SEASON,
					BaseItemKind.EPISODE,
					BaseItemKind.BOX_SET,
					BaseItemKind.PLAYLIST,
					BaseItemKind.VIDEO,
					BaseItemKind.MUSIC_VIDEO,
				),
				recursive = true,
				fields = ItemRepository.browseFields + ItemFields.TAGS + ItemFields.STUDIOS,
				imageTypeLimit = 1,
			).content.items
		}
	} catch (err: kotlinx.coroutines.CancellationException) {
		throw err
	} catch (err: Exception) {
		Timber.e(err, "Failed to load favorite items")
		null
	}

	/**
	 * The person's favourite channels, the guide's Favorites (Jellyfin's favourites, in the guide's
	 * order), with what is on now.
	 */
	private suspend fun loadChannels(): List<BaseItemDto>? = try {
		val sortDatePlayed = liveTvPreferences[LiveTvPreferences.channelOrder] == ItemSortBy.DATE_PLAYED.serialName
		withContext(Dispatchers.IO) {
			api.liveTvApi.getLiveTvChannels(
				isFavorite = true,
				addCurrentProgram = true,
				enableUserData = true,
				fields = listOf(ItemFields.TAGS),
				sortBy = if (sortDatePlayed) setOf(ItemSortBy.DATE_PLAYED) else setOf(ItemSortBy.SORT_NAME),
				sortOrder = if (sortDatePlayed) SortOrder.DESCENDING else SortOrder.ASCENDING,
			).content.items.let(::withoutChannelSeparators)
		}
	} catch (err: kotlinx.coroutines.CancellationException) {
		throw err
	} catch (err: Exception) {
		Timber.e(err, "Failed to load favorite channels")
		null
	}

	private suspend fun loadPeople(): List<BaseItemDto>? = try {
		withContext(Dispatchers.IO) {
			api.personsApi.getPersons(isFavorite = true).content.items
		}
	} catch (err: kotlinx.coroutines.CancellationException) {
		throw err
	} catch (err: Exception) {
		Timber.e(err, "Failed to load favorite people")
		null
	}
}
