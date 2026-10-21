package org.jellyfin.androidtv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.data.repository.DiscoverItem
import org.jellyfin.androidtv.data.repository.TentacleRepository
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class SearchViewModel(
	private val tentacleRepository: TentacleRepository,
	private val librarySearch: LibrarySearch,
) : ViewModel() {
	companion object {
		private val debounceDuration = 600.milliseconds
	}

	private var searchJob: Job? = null
	private var previousQuery: String? = null

	private val _tmdbResultsFlow = MutableStateFlow<List<DiscoverItem>>(emptyList())
	val tmdbResultsFlow = _tmdbResultsFlow.asStateFlow()

	private val _isTmdbSearching = MutableStateFlow(false)
	val isTmdbSearching = _isTmdbSearching.asStateFlow()

	// Live TV channels from Jellyfin: their cards tune the channel.
	private val _channelResultsFlow = MutableStateFlow<List<BaseItemDto>>(emptyList())
	val channelResultsFlow = _channelResultsFlow.asStateFlow()

	// Library items from Jellyfin (recordings, YouTube-library videos, …), without the owned
	// titles the Discover row already shows.
	private val _libraryMatches = MutableStateFlow<List<BaseItemDto>>(emptyList())
	val libraryResultsFlow: StateFlow<List<BaseItemDto>> = combine(_libraryMatches, _tmdbResultsFlow, ::libraryRowItems)
		.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

	private var librarySearchJob: Job? = null

	fun searchImmediately(query: String) = searchDebounced(query, 0.milliseconds)

	fun forceRefresh() {
		val query = previousQuery ?: return
		if (query.isBlank()) return
		previousQuery = null
		searchImmediately(query)
	}

	fun searchDebounced(query: String, debounce: Duration = debounceDuration): Boolean {
		val trimmed = query.trim()
		if (trimmed == previousQuery) return false
		previousQuery = trimmed

		searchJob?.cancel()
		librarySearchJob?.cancel()

		if (trimmed.isBlank()) {
			_tmdbResultsFlow.value = emptyList()
			_channelResultsFlow.value = emptyList()
			_libraryMatches.value = emptyList()
			_isTmdbSearching.value = false
			return true
		}

		// Jellyfin answers in well under a second; don't make its rows wait for TMDB.
		librarySearchJob = viewModelScope.launch {
			delay(debounce)
			launch { _channelResultsFlow.value = librarySearch.channels(trimmed) }
			launch { _libraryMatches.value = librarySearch.library(trimmed) }
		}

		searchJob = viewModelScope.launch {
			delay(debounce)
			_isTmdbSearching.value = true

			try {
				_tmdbResultsFlow.value = tentacleRepository.searchDiscover(trimmed)
			} catch (e: CancellationException) {
				// A newer keystroke cancelled this search (debounce). Let cancellation
				// propagate — don't log it as an error or blank the results the newer
				// search is about to populate.
				throw e
			} catch (e: Exception) {
				Timber.e(e, "Failed to search TMDB via Tentacle")
				_tmdbResultsFlow.value = emptyList()
			}

			_isTmdbSearching.value = false
		}

		return true
	}
}
