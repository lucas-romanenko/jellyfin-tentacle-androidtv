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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.data.repository.DISCOVER_UNREACHABLE
import org.jellyfin.androidtv.data.repository.DiscoverSearchOutcome
import org.jellyfin.androidtv.data.repository.TentacleRepository
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber
import kotlin.time.Duration

class SearchViewModel(
	private val tentacleRepository: TentacleRepository,
	private val librarySearch: LibrarySearch,
) : ViewModel() {
	private var searchJob: Job? = null
	private var librarySearchJob: Job? = null
	private var previousQuery: String? = null

	/** The card opened last ([searchCardKey]); Search focuses it again when you come back. */
	var lastOpenedCard: String? = null

	// Discover (TMDB via Tentacle): the last good results stay while a newer search runs or fails.
	private val _discoverState = MutableStateFlow(DiscoverSearchState())
	val discoverState: StateFlow<DiscoverSearchState> = _discoverState.asStateFlow()

	// Live TV channels from Jellyfin: their cards tune the channel.
	private val _channelResultsFlow = MutableStateFlow<List<BaseItemDto>>(emptyList())
	val channelResultsFlow = _channelResultsFlow.asStateFlow()

	// Library items from Jellyfin (recordings, YouTube-library videos, …), without the owned
	// titles the Discover row already shows.
	private val _libraryMatches = MutableStateFlow<List<BaseItemDto>>(emptyList())
	val libraryResultsFlow: StateFlow<List<BaseItemDto>> =
		combine(_libraryMatches, _discoverState) { library, discover -> libraryRowItems(library, discover.items) }
			.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

	fun searchImmediately(query: String) = searchDebounced(query, Duration.ZERO)

	fun forceRefresh() {
		val query = previousQuery ?: return
		if (query.isBlank()) return
		previousQuery = null
		searchImmediately(query)
	}

	fun searchDebounced(query: String, debounce: Duration = SearchQueries.debounce): Boolean {
		val trimmed = SearchQueries.normalize(query)
		if (trimmed == previousQuery) return false
		previousQuery = trimmed

		// A newer query: drop the searches for the old one (their HTTP calls are cancelled too).
		searchJob?.cancel()
		librarySearchJob?.cancel()

		_discoverState.update { it.started(trimmed) }

		if (!SearchQueries.searchesLibrary(trimmed)) {
			_channelResultsFlow.value = emptyList()
			_libraryMatches.value = emptyList()
			return true
		}

		// Jellyfin answers in well under a second; don't make its rows wait for TMDB.
		librarySearchJob = viewModelScope.launch {
			delay(debounce)
			launch { _channelResultsFlow.value = librarySearch.channels(trimmed) }
			launch { _libraryMatches.value = librarySearch.library(trimmed) }
		}

		if (!SearchQueries.searchesDiscover(trimmed)) return true

		searchJob = viewModelScope.launch {
			delay(debounce)
			val outcome = try {
				tentacleRepository.searchDiscover(trimmed)
			} catch (e: CancellationException) {
				// A newer keystroke cancelled this search: leave the row to the newer one.
				throw e
			} catch (e: Exception) {
				Timber.e(e, "Failed to search TMDB via Tentacle")
				DiscoverSearchOutcome.Failed(DISCOVER_UNREACHABLE)
			}
			// answered() ignores an answer for a query the user has typed past.
			_discoverState.update { it.answered(trimmed, outcome) }
		}

		return true
	}
}
