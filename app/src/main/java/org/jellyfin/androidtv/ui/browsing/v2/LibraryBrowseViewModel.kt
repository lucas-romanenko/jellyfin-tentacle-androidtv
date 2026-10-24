package org.jellyfin.androidtv.ui.browsing.v2

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.GridDirection
import org.jellyfin.androidtv.constant.ImageType
import org.jellyfin.androidtv.constant.PosterSize
import org.jellyfin.androidtv.data.repository.ItemRepository
import org.jellyfin.androidtv.data.repository.MultiServerRepository
import org.jellyfin.androidtv.preference.LibraryPreferences
import org.jellyfin.androidtv.preference.PreferencesRepository
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.util.sdk.ApiClientFactory
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.artistsApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SeriesStatus
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.UserItemDataDto
import timber.log.Timber
import java.util.UUID

enum class SeriesStatusFilter(@StringRes val labelRes: Int) {
	ALL(R.string.lbl_all_items),
	CONTINUING(R.string.lbl__continuing_title),
	ENDED(R.string.lbl_ended_title)
}
enum class PlayedStatusFilter(@StringRes val labelRes: Int) {
	ALL(R.string.lbl_all_items),
	WATCHED(R.string.lbl_watched),
	UNWATCHED(R.string.lbl_unwatched)
}

data class SortOption(
	@StringRes val nameRes: Int,
	val sortBy: ItemSortBy,
	val sortOrder: SortOrder,
)

data class LibraryBrowseUiState(
	val isLoading: Boolean = true,
	val libraryName: String = "",
	val collectionType: CollectionType? = null,
	val items: List<BaseItemDto> = emptyList(),
	val totalItems: Int = 0,
	val currentSortOption: SortOption = SortOption(R.string.lbl_name, ItemSortBy.SORT_NAME, SortOrder.ASCENDING),
	val filterFavorites: Boolean = false,
	val filterPlayed: PlayedStatusFilter = PlayedStatusFilter.ALL,
	val filterSeriesStatus: SeriesStatusFilter = SeriesStatusFilter.ALL,
	val startLetter: String? = null,
	val hasMoreItems: Boolean = false,
	val posterSize: PosterSize = PosterSize.MED,
	val imageType: ImageType = ImageType.POSTER,
	val gridDirection: GridDirection = GridDirection.VERTICAL,
	val useAutoImageType: Boolean = false,
	val isGenreMode: Boolean = false,
	val genreName: String? = null,
	val displayPreferencesId: String? = null,
	val parentItemId: UUID? = null,
)

class LibraryBrowseViewModel(
	private val api: ApiClient,
	private val apiClientFactory: ApiClientFactory,
	private val preferencesRepository: PreferencesRepository,
	private val multiServerRepository: MultiServerRepository,
	private val userPreferences: UserPreferences,
) : ViewModel() {

	private val _uiState = MutableStateFlow(LibraryBrowseUiState())
	val uiState: StateFlow<LibraryBrowseUiState> = _uiState.asStateFlow()

	// Kept out of uiState: the focus moves with every D-pad press, and only the header shows it.
	private val _focusedItem = MutableStateFlow<BaseItemDto?>(null)
	val focusedItem: StateFlow<BaseItemDto?> = _focusedItem.asStateFlow()

	var effectiveApi: ApiClient = api
		private set

	var serverId: UUID? = null
		private set

	var savedScrollIndex: Int = 0
	var savedScrollOffset: Int = 0
	// The focused poster's position, for the counter; kept out of uiState like the focused item.
	private val _focusedIndex = MutableStateFlow(0)
	val focusedIndex: StateFlow<Int> = _focusedIndex.asStateFlow()
	var savedFocusedIndex: Int
		get() = _focusedIndex.value
		set(value) {
			_focusedIndex.value = value
		}
	var hasRestoredScroll: Boolean = false
	private var isInitialized: Boolean = false

	private var folder: BaseItemDto? = null
	private var libraryPreferences: LibraryPreferences? = null
	private var currentPage = 0
	private var isLoadingMore = false
	// Bumped by every reset, so user data that arrives for an older page is dropped.
	private var loadGeneration = 0
	private val pageSize = 100

	private var genreFilter: String? = null
	private var includeType: String? = null
	private var genreParentId: UUID? = null

	val sortOptions: List<SortOption> by lazy {
		buildList {
			add(SortOption(R.string.lbl_name, ItemSortBy.SORT_NAME, SortOrder.ASCENDING))
			if (folder?.collectionType == CollectionType.TVSHOWS) {
				add(SortOption(R.string.lbl_date_episode_added, ItemSortBy.DATE_LAST_CONTENT_ADDED, SortOrder.DESCENDING))
				add(SortOption(R.string.lbl_date_series_added, ItemSortBy.DATE_CREATED, SortOrder.DESCENDING))
			} else {
				add(SortOption(R.string.lbl_date_added, ItemSortBy.DATE_CREATED, SortOrder.DESCENDING))
			}
			add(SortOption(R.string.lbl_premier_date, ItemSortBy.PREMIERE_DATE, SortOrder.DESCENDING))
			add(SortOption(R.string.lbl_rating, ItemSortBy.OFFICIAL_RATING, SortOrder.ASCENDING))
			add(SortOption(R.string.lbl_community_rating, ItemSortBy.COMMUNITY_RATING, SortOrder.DESCENDING))
			add(SortOption(R.string.lbl_critic_rating, ItemSortBy.CRITIC_RATING, SortOrder.DESCENDING))
			if (folder?.collectionType == CollectionType.TVSHOWS) {
				add(SortOption(R.string.lbl_last_played, ItemSortBy.SERIES_DATE_PLAYED, SortOrder.DESCENDING))
			} else {
				add(SortOption(R.string.lbl_last_played, ItemSortBy.DATE_PLAYED, SortOrder.DESCENDING))
			}
			if (folder?.collectionType == CollectionType.MOVIES) {
				add(SortOption(R.string.lbl_runtime, ItemSortBy.RUNTIME, SortOrder.ASCENDING))
			}
		}
	}

	fun initialize(folderJson: String, serverId: UUID?, userId: UUID?, includeType: String? = null) {
		if (isInitialized) return
		isInitialized = true

		val folder = kotlinx.serialization.json.Json.decodeFromString(
			BaseItemDto.serializer(), folderJson
		)
		this.folder = folder
		this.serverId = serverId
		this.includeType = includeType

		resolveApiClient(serverId, userId)

		val autoImage = folder.collectionType !in setOf(
			CollectionType.MOVIES, CollectionType.TVSHOWS,
			CollectionType.MUSIC, CollectionType.LIVETV,
		)
		_uiState.value = LibraryBrowseUiState(
			isLoading = true,
			libraryName = folder.name ?: "",
			collectionType = folder.collectionType,
			useAutoImageType = autoImage,
		)

		viewModelScope.launch {
			// Load library display preferences on IO thread
			val dispPrefId = folder.displayPreferencesId
			if (dispPrefId != null) {
				libraryPreferences = withContext(Dispatchers.IO) {
					preferencesRepository.getLibraryPreferences(dispPrefId, effectiveApi)
				}
			}

			// Apply saved preferences
			val savedSort = libraryPreferences?.get(LibraryPreferences.sortBy)
			val savedOrder = libraryPreferences?.get(LibraryPreferences.sortOrder)
			val savedFavorites = libraryPreferences?.get(LibraryPreferences.filterFavoritesOnly) ?: false
			val savedPlayed = libraryPreferences?.get(LibraryPreferences.filterPlayedStatus) ?: PlayedStatusFilter.ALL
			val savedSeries = libraryPreferences?.get(LibraryPreferences.filterSeriesStatus) ?: SeriesStatusFilter.ALL

			val initialSort = if (savedSort != null && savedOrder != null) {
				sortOptions.find { it.sortBy == savedSort }?.copy(sortOrder = savedOrder)
					?: SortOption(R.string.lbl_name, ItemSortBy.SORT_NAME, SortOrder.ASCENDING)
			} else {
				SortOption(R.string.lbl_name, ItemSortBy.SORT_NAME, SortOrder.ASCENDING)
			}

			val savedPosterSize = libraryPreferences?.get(LibraryPreferences.posterSize) ?: PosterSize.MED
			val savedImageType = if (folder.collectionType == CollectionType.MUSIC) ImageType.SQUARE
				else libraryPreferences?.get(LibraryPreferences.imageType) ?: ImageType.POSTER
			val savedGridDirection = libraryPreferences?.get(LibraryPreferences.gridDirection) ?: GridDirection.VERTICAL

			_uiState.value = _uiState.value.copy(
				currentSortOption = initialSort,
				filterFavorites = savedFavorites,
				filterPlayed = savedPlayed,
				filterSeriesStatus = savedSeries,
				posterSize = savedPosterSize,
				imageType = savedImageType,
				gridDirection = savedGridDirection,
			)

			loadItems(reset = true)
		}
	}

	/**
	 * Initialize in genre mode — browse items filtered by a specific genre.
	 */
	fun initializeGenre(
		genreName: String,
		parentId: UUID?,
		includeType: String?,
		serverId: UUID?,
		userId: UUID?,
		displayPreferencesId: String? = null,
		parentItemId: UUID? = null,
	) {
		if (isInitialized) return
		isInitialized = true

		this.genreFilter = genreName
		this.genreParentId = parentId
		this.includeType = includeType
		this.serverId = serverId

		resolveApiClient(serverId, userId)

		// Set initial loading state
		_uiState.value = LibraryBrowseUiState(
			isLoading = true,
			libraryName = genreName,
			isGenreMode = true,
			genreName = genreName,
			displayPreferencesId = displayPreferencesId,
			parentItemId = parentItemId,
			currentSortOption = SortOption(R.string.lbl_name, ItemSortBy.SORT_NAME, SortOrder.ASCENDING),
		)

		if (displayPreferencesId != null) {
			viewModelScope.launch {
				libraryPreferences = withContext(Dispatchers.IO) {
					preferencesRepository.getLibraryPreferences(displayPreferencesId, effectiveApi)
				}

				val savedPosterSize = libraryPreferences?.get(LibraryPreferences.posterSize) ?: PosterSize.MED
				val savedImageType = libraryPreferences?.get(LibraryPreferences.imageType) ?: ImageType.POSTER
				val savedGridDirection = libraryPreferences?.get(LibraryPreferences.gridDirection) ?: GridDirection.VERTICAL

				_uiState.value = _uiState.value.copy(
					posterSize = savedPosterSize,
					imageType = savedImageType,
					gridDirection = savedGridDirection,
				)

				loadItems(reset = true)
			}
		} else {
			loadItems(reset = true)
		}
	}

	private fun resolveApiClient(serverId: UUID?, userId: UUID?) {
		if (serverId != null) {
			val serverApi = if (userId != null) {
				apiClientFactory.getApiClient(serverId, userId)
			} else {
				apiClientFactory.getApiClientForServer(serverId)
			}
			if (serverApi != null) effectiveApi = serverApi
		}
	}

	fun setSortOption(sortOption: SortOption) {
		_uiState.value = _uiState.value.copy(currentSortOption = sortOption)
		savePreferences()
		loadItems(reset = true)
	}

	fun toggleFavorites() {
		_uiState.value = _uiState.value.copy(filterFavorites = !_uiState.value.filterFavorites)
		savePreferences()
		loadItems(reset = true)
	}

	fun setPlayedFilter(filter: PlayedStatusFilter) {
		_uiState.value = _uiState.value.copy(filterPlayed = filter)
		savePreferences()
		loadItems(reset = true)
	}

	fun setSeriesStatusFilter(filter: SeriesStatusFilter) {
		_uiState.value = _uiState.value.copy(filterSeriesStatus = filter)
		loadItems(reset = true)
		savePreferences()
	}

	fun setStartLetter(letter: String?) {
		_uiState.value = _uiState.value.copy(startLetter = letter)
		loadItems(reset = true)
	}

	fun loadMore() {
		if (!isLoadingMore && _uiState.value.hasMoreItems) {
			loadItems(reset = false)
		}
	}

	fun setFocusedItem(item: BaseItemDto) {
		_focusedItem.value = item
	}

	/**
	 * Re-read display preferences (posterSize, imageType, gridDirection) without reloading items.
	 * Returns true if any display setting changed.
	 */
	fun refreshDisplayPreferences(): Boolean {
		val prefs = libraryPreferences ?: return false
		val newPosterSize = prefs.get(LibraryPreferences.posterSize)
		val newImageType = prefs.get(LibraryPreferences.imageType)
		val newGridDirection = prefs.get(LibraryPreferences.gridDirection)
		val changed = newPosterSize != _uiState.value.posterSize ||
			newImageType != _uiState.value.imageType ||
			newGridDirection != _uiState.value.gridDirection
		if (changed) {
			_uiState.value = _uiState.value.copy(
				posterSize = newPosterSize,
				imageType = newImageType,
				gridDirection = newGridDirection,
			)
		}
		return changed
	}

	private fun savePreferences() {
		val prefs = libraryPreferences ?: return
		viewModelScope.launch {
			prefs.set(LibraryPreferences.filterFavoritesOnly, _uiState.value.filterFavorites)
			prefs.set(LibraryPreferences.filterPlayedStatus, _uiState.value.filterPlayed)
			prefs.set(LibraryPreferences.filterSeriesStatus, _uiState.value.filterSeriesStatus)
			prefs.set(LibraryPreferences.sortBy, _uiState.value.currentSortOption.sortBy)
			prefs.set(LibraryPreferences.sortOrder, _uiState.value.currentSortOption.sortOrder)
			prefs.commit()
		}
	}

	private fun loadItems(reset: Boolean) {
		val isGenre = _uiState.value.isGenreMode
		val folder = this.folder
		if (!isGenre && folder == null) return
		if (isLoadingMore && !reset) return

		viewModelScope.launch {
			if (reset) {
				loadGeneration++
				currentPage = 0
				savedScrollIndex = 0
				savedScrollOffset = 0
				savedFocusedIndex = 0
				hasRestoredScroll = false
				_uiState.value = _uiState.value.copy(isLoading = true, items = emptyList())
				_focusedItem.value = null
			}
			isLoadingMore = true
			val generation = loadGeneration

			try {
				val state = _uiState.value
				// A folders library (the DVR's Recordings, home videos) lists plain folders, and
				// Jellyfin 10.11 fills each folder's user data (its unplayed count) with a query over
				// the whole library before it sends the page: 1-1.5 s a folder on a big server, empty
				// folders too (#204). Load the page without it; fillUserData adds it afterwards.
				val deferUserData = deferLibraryUserData(isGenre, includeType, state.useAutoImageType)

				// Build filters
				val filters = buildSet {
					if (state.filterFavorites) add(ItemFilter.IS_FAVORITE)
					when (state.filterPlayed) {
						PlayedStatusFilter.WATCHED -> add(ItemFilter.IS_PLAYED)
						PlayedStatusFilter.UNWATCHED -> add(ItemFilter.IS_UNPLAYED)
						PlayedStatusFilter.ALL -> {} // no filter
					}
				}

				val includeTypes: Set<BaseItemKind>?
				val excludeTypes: Set<BaseItemKind>?
				val recursive: Boolean
				val parentId: UUID?
				val genres: Set<String>?

				if (isGenre) {
					// Genre mode: filter by genre name
					parentId = genreParentId
					genres = genreFilter?.let { setOf(it) }
					recursive = true
					includeTypes = when (includeType) {
						"Movie" -> setOf(BaseItemKind.MOVIE)
						"Series" -> setOf(BaseItemKind.SERIES)
						else -> setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)
					}
					excludeTypes = null
				} else if (includeType in setOf("AlbumArtist", "Artist")) {
					parentId = folder!!.id
					genres = null
					includeTypes = null
					excludeTypes = null
					recursive = false
				} else {
					parentId = folder!!.id
					genres = null

					val isLibraryRoot = folder.type == BaseItemKind.USER_VIEW ||
						folder.type == BaseItemKind.COLLECTION_FOLDER

					includeTypes = when {
						isLibraryRoot -> {
							when (folder.collectionType) {
								CollectionType.MOVIES -> setOf(BaseItemKind.MOVIE)
								CollectionType.TVSHOWS -> setOf(BaseItemKind.SERIES)
								CollectionType.MUSIC -> setOf(BaseItemKind.MUSIC_ALBUM)
								else -> null
							}
						}
						else -> null
					}

					recursive = isLibraryRoot && includeTypes != null

					excludeTypes = when {
						(folder.type == BaseItemKind.USER_VIEW || folder.type == BaseItemKind.COLLECTION_FOLDER) &&
							folder.collectionType == CollectionType.MOVIES -> setOf(BaseItemKind.BOX_SET)
						else -> null
					}
				}

				val seriesStatus = when (state.filterSeriesStatus) {
					SeriesStatusFilter.CONTINUING -> setOf(SeriesStatus.CONTINUING)
					SeriesStatusFilter.ENDED -> setOf(SeriesStatus.ENDED)
					else -> null
				}

				val response = when (includeType) {
					"AlbumArtist" -> withContext(Dispatchers.IO) {
						effectiveApi.artistsApi.getAlbumArtists(
							parentId = parentId,
							fields = ItemRepository.itemFields,
							sortBy = setOf(state.currentSortOption.sortBy),
							sortOrder = setOf(state.currentSortOption.sortOrder),
							filters = filters,
							startIndex = currentPage * pageSize,
							limit = pageSize,
							enableTotalRecordCount = true,
							nameStartsWith = state.startLetter,
						).content
					}
					"Artist" -> withContext(Dispatchers.IO) {
						effectiveApi.artistsApi.getArtists(
							parentId = parentId,
							fields = ItemRepository.itemFields,
							sortBy = setOf(state.currentSortOption.sortBy),
							sortOrder = setOf(state.currentSortOption.sortOrder),
							filters = filters,
							startIndex = currentPage * pageSize,
							limit = pageSize,
							enableTotalRecordCount = true,
							nameStartsWith = state.startLetter,
						).content
					}
					else -> withContext(Dispatchers.IO) {
						effectiveApi.itemsApi.getItems(
							parentId = parentId,
							genres = genres,
							includeItemTypes = includeTypes,
							excludeItemTypes = excludeTypes,
							collapseBoxSetItems = false,
							recursive = recursive,
							fields = ItemRepository.itemFields,
							sortBy = setOf(state.currentSortOption.sortBy),
							sortOrder = setOf(state.currentSortOption.sortOrder),
							filters = filters,
							seriesStatus = seriesStatus,
							startIndex = currentPage * pageSize,
							limit = pageSize,
							enableTotalRecordCount = true,
							nameStartsWith = state.startLetter,
							enableUserData = if (deferUserData) false else null,
						).content
					}
				}

				val totalItems = response.totalRecordCount ?: 0
				val newItems = response.items

				// Annotate with serverId for cross-server support
				val annotatedItems = if (serverId != null) {
					newItems.map { item ->
						item.copy(serverId = serverId.toString())
					}
				} else {
					newItems
				}

				val allItems = if (reset) annotatedItems.distinctBy { it.id }
					else appendDistinctBy(_uiState.value.items, annotatedItems) { it.id }
				currentPage++

				_uiState.value = _uiState.value.copy(
					isLoading = false,
					items = allItems,
					totalItems = totalItems,
					// Counted by what the server sent, so a dropped repeat can't stop paging
					hasMoreItems = currentPage * pageSize < totalItems && newItems.isNotEmpty(),
				)
				if (reset) _focusedItem.value = allItems.firstOrNull()
				// Its own coroutine: loadMore must not wait for the folders' counts.
				if (deferUserData && annotatedItems.isNotEmpty()) {
					viewModelScope.launch { fillUserData(annotatedItems, generation) }
				}
			} catch (err: Exception) {
				coroutineContext.ensureActive()
				Timber.e(err, "Failed to load library items")
				_uiState.value = _uiState.value.copy(isLoading = false)
			} finally {
				isLoadingMore = false
			}
		}
	}

	/**
	 * Adds the user data a folders-library page was loaded without (see loadItems): the videos'
	 * first, one quick request, then the folders' (their unplayed counts are the slow part), so
	 * watched marks show at once and folder badges follow. A failed request leaves the grid as it is.
	 */
	private suspend fun fillUserData(page: List<BaseItemDto>, generation: Int) {
		val (folders, videos) = page.partition { it.isFolder == true }
		for (batch in listOf(videos, folders)) {
			if (batch.isEmpty()) continue
			val userData = try {
				withContext(Dispatchers.IO) {
					effectiveApi.itemsApi.getItems(ids = batch.map { it.id }, enableImages = false).content.items
				}.associate { it.id to it.userData }
			} catch (err: Exception) {
				coroutineContext.ensureActive()
				Timber.w(err, "Failed to load user data for library items")
				continue
			}
			if (generation != loadGeneration) return
			_uiState.value = _uiState.value.copy(items = withUserData(_uiState.value.items, userData))
			_focusedItem.value?.let { focused ->
				userData[focused.id]?.let { _focusedItem.value = focused.copy(userData = it) }
			}
		}
	}
}

/**
 * Whether a page is loaded without user data and filled in afterwards: only in a folders library
 * (the collection type is not movies, TV shows, music or Live TV), whose plain folders make
 * Jellyfin count every folder's unplayed items before it answers (#204).
 */
internal fun deferLibraryUserData(isGenre: Boolean, includeType: String?, useAutoImageType: Boolean) =
	!isGenre && includeType == null && useAutoImageType

/** [items] with the user data in [userData] (by id) put on them; the others as they were. */
internal fun withUserData(items: List<BaseItemDto>, userData: Map<UUID, UserItemDataDto?>) =
	items.map { item -> userData[item.id]?.let { item.copy(userData = it) } ?: item }

/**
 * The grid's counter: the focused poster's number (from 1) and how many there are, "1 | 14532".
 * Empty while nothing has loaded; the loaded count stands in when the server sent no total.
 */
internal fun libraryCounterText(focusedIndex: Int, loadedItems: Int, totalItems: Int): String {
	val total = maxOf(totalItems, loadedItems)
	if (total <= 0) return ""
	return "${focusedIndex.coerceIn(0, total - 1) + 1} | $total"
}
