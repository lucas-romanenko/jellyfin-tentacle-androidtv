package org.jellyfin.androidtv.ui.search

import org.jellyfin.androidtv.data.repository.DiscoverItem
import org.jellyfin.androidtv.data.repository.DiscoverSearchOutcome
import kotlin.time.Duration.Companion.milliseconds

/**
 * When the Search tab asks Jellyfin and Tentacle. The TV keyboard sends every keystroke
 * ("t", "ts", "tsn"), and Tentacle's Discover search is slow per request: searching each one
 * queued requests on the server until the last one timed out.
 */
object SearchQueries {
	/** Pause in typing before a search runs. */
	val debounce = 350.milliseconds

	/** Shortest query sent to Tentacle's Discover search (one letter matches all of TMDB). */
	const val DISCOVER_MIN_LENGTH = 2

	fun normalize(query: String): String = query.trim()

	/** Jellyfin (channels, library) answers fast: any non-blank query. */
	fun searchesLibrary(query: String): Boolean = normalize(query).isNotEmpty()

	fun searchesDiscover(query: String): Boolean = normalize(query).length >= DISCOVER_MIN_LENGTH
}

/**
 * The Discover row of the Search tab.
 *
 * [query] is the latest query typed; [items] are the last good results, kept on screen while a
 * newer search runs and when it fails or times out, so a slow or busy server doesn't blank them.
 * [failure] is set only when the latest search failed and there was nothing to keep.
 */
data class DiscoverSearchState(
	val query: String = "",
	val items: List<DiscoverItem> = emptyList(),
	val searching: Boolean = false,
	val failure: String? = null,
) {
	/** [query] was typed. A query too short for Discover clears the row (its titles answer another query). */
	fun started(query: String): DiscoverSearchState {
		val q = SearchQueries.normalize(query)
		return if (!SearchQueries.searchesDiscover(q)) DiscoverSearchState(query = q)
		else copy(query = q, searching = true, failure = null)
	}

	/** The search for [forQuery] answered. An answer for a query typed past is ignored. */
	fun answered(forQuery: String, outcome: DiscoverSearchOutcome): DiscoverSearchState {
		if (SearchQueries.normalize(forQuery) != query) return this
		return when (outcome) {
			is DiscoverSearchOutcome.Found -> DiscoverSearchState(query = query, items = outcome.items)
			is DiscoverSearchOutcome.Failed -> copy(
				searching = false,
				failure = if (items.isEmpty()) outcome.message else null,
			)
		}
	}

	/** The "busy" / "unavailable" line: only when Discover has nothing on screen and isn't still trying. */
	val message: String?
		get() = failure.takeIf { !searching && items.isEmpty() }

	/** Show the Discover row: its titles, or "Searching..." while the first answer is awaited. */
	val showRow: Boolean
		get() = items.isNotEmpty() || searching
}
