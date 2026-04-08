package org.jellyfin.androidtv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.data.repository.DiscoverItem
import org.jellyfin.androidtv.data.repository.TentacleRepository
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class SearchViewModel(
	private val tentacleRepository: TentacleRepository,
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

	fun searchImmediately(query: String) = searchDebounced(query, 0.milliseconds)

	fun searchDebounced(query: String, debounce: Duration = debounceDuration): Boolean {
		val trimmed = query.trim()
		if (trimmed == previousQuery) return false
		previousQuery = trimmed

		searchJob?.cancel()

		if (trimmed.isBlank()) {
			_tmdbResultsFlow.value = emptyList()
			_isTmdbSearching.value = false
			return true
		}

		searchJob = viewModelScope.launch {
			delay(debounce)
			_isTmdbSearching.value = true

			try {
				_tmdbResultsFlow.value = tentacleRepository.searchDiscover(trimmed)
			} catch (e: Exception) {
				Timber.e(e, "Failed to search TMDB via Tentacle")
				_tmdbResultsFlow.value = emptyList()
			}

			_isTmdbSearching.value = false
		}

		return true
	}
}
