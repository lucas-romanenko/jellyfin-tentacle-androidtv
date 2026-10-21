package org.jellyfin.androidtv.ui.search

import kotlinx.coroutines.CancellationException
import org.jellyfin.androidtv.constant.QueryDefaults
import org.jellyfin.androidtv.data.repository.DiscoverItem
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber

/**
 * The Jellyfin side of the Search tab: Live TV channels and library items (recordings, YouTube-library
 * videos, home videos, anything TMDB doesn't know). The Discover search (TMDB, Radarr/Sonarr) can't
 * find these, and the channel entries it adds can't be opened as Discover cards (#75, #82).
 */
class LibrarySearch(
	private val searchRepository: SearchRepository,
	private val hideItem: (BaseItemDto) -> Boolean = { false },
) {
	companion object {
		/** Asked from Jellyfin before ranking, so word-start matches aren't cut off by in-word ones. */
		const val CHANNEL_FETCH_LIMIT = 100
		const val CHANNEL_ROW_LIMIT = 30

		val channelKinds = setOf(BaseItemKind.TV_CHANNEL)
		val libraryKinds = setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE, BaseItemKind.VIDEO)
	}

	/** Channels matching [query], tunable from the card. Empty on any failure (Live TV off, no access). */
	suspend fun channels(query: String): List<BaseItemDto> {
		val found = fetch("channels", query, channelKinds, CHANNEL_FETCH_LIMIT)
		return rankChannelMatches(query, found.filterNot(hideItem)).take(CHANNEL_ROW_LIMIT)
	}

	/** Library items matching [query]. Empty on any failure. */
	suspend fun library(query: String): List<BaseItemDto> =
		fetch("library", query, libraryKinds, QueryDefaults.SEARCH_PAGE_SIZE).filterNot(hideItem)

	private suspend fun fetch(
		what: String,
		query: String,
		kinds: Collection<BaseItemKind>,
		limit: Int,
	): List<BaseItemDto> = try {
		searchRepository.search(query, kinds, limit).getOrElse {
			Timber.w(it, "Search: Jellyfin $what search failed")
			emptyList()
		}
	} catch (e: CancellationException) {
		throw e
	} catch (e: Exception) {
		Timber.w(e, "Search: Jellyfin $what search failed")
		emptyList()
	}
}

/**
 * Jellyfin matches the search term anywhere in a channel name, so "TSN" also finds "SPORTSNET",
 * and lists them in its own order ("TSN 5" before "TSN 1"). Names that start with the query come
 * first, then names with a word that starts with it, then the rest; A-Z within each group.
 */
internal fun rankChannelMatches(query: String, channels: List<BaseItemDto>): List<BaseItemDto> {
	val q = query.trim().lowercase()
	if (q.isEmpty()) return channels
	return channels.sortedWith(
		compareBy<BaseItemDto>({ channelMatchTier(q, it.name.orEmpty().lowercase()) }, { it.name.orEmpty().lowercase() })
	)
}

private fun channelMatchTier(q: String, name: String): Int {
	if (name.startsWith(q)) return 0
	var at = name.indexOf(q)
	while (at > 0) {
		if (!name[at - 1].isLetterOrDigit()) return 1
		at = name.indexOf(q, at + 1)
	}
	return 2
}

/**
 * Library items to show in their own row. Movies and series that the Discover results already show
 * as "In Library" cards (same TMDB id) are left out, so an owned title isn't listed twice.
 */
internal fun libraryRowItems(library: List<BaseItemDto>, discover: List<DiscoverItem>): List<BaseItemDto> {
	val ownedMovies = discover.filter { it.inLibrary && it.tmdbId > 0 && it.mediaType == "movie" }.map { it.tmdbId.toString() }.toSet()
	val ownedSeries = discover.filter { it.inLibrary && it.tmdbId > 0 && it.mediaType != "movie" }.map { it.tmdbId.toString() }.toSet()
	if (ownedMovies.isEmpty() && ownedSeries.isEmpty()) return library
	return library.filterNot { item ->
		val tmdb = item.providerIds?.entries?.firstOrNull { it.key.equals("Tmdb", ignoreCase = true) }?.value
		when {
			tmdb.isNullOrBlank() -> false
			item.type == BaseItemKind.MOVIE -> tmdb in ownedMovies
			item.type == BaseItemKind.SERIES -> tmdb in ownedSeries
			else -> false
		}
	}
}
