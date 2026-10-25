package org.jellyfin.androidtv.ui.browsing.v2

import org.jellyfin.androidtv.ui.playback.segment.youtubeVideoId
import org.jellyfin.androidtv.util.sdk.isLiveTvRecording
import org.jellyfin.androidtv.util.sdk.youTubeChannelFromPath
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * The Favourites screen's rows (Lucas, 24 Oct: "should simply show row for favorite movies, row
 * for favorite shows and row for favorite channels"): films, shows, then the favourite channels.
 * A row with nothing in it isn't shown.
 */
enum class FavoriteRowKind(val wide: Boolean) {
	MOVIES(wide = false),
	SHOWS(wide = false),
	CHANNELS(wide = true),
}

data class FavoriteRow(val kind: FavoriteRowKind, val items: List<BaseItemDto>)

/**
 * True for a video from Tentacle's YouTube library, as Tentacle web's Favorites decides: its
 * "yt:<slug>" tag, the YouTube genre, its video id or its .strm folder. Not the bare "youtube"
 * tag: TMDB gives that keyword to ordinary films too (Bo Burnham: Inside).
 */
fun isFavoriteYouTubeVideo(item: BaseItemDto): Boolean {
	if (item.type != BaseItemKind.MOVIE && item.type != BaseItemKind.VIDEO) return false
	return item.tags.orEmpty().any { it.trim().startsWith("yt:", ignoreCase = true) }
		|| item.genres.orEmpty().any { it.trim().equals("YouTube", ignoreCase = true) }
		|| youtubeVideoId(item.providerIds) != null
		|| youTubeChannelFromPath(item.path) != null
}

/**
 * Sorts the favourites into rows: [items] (films and shows, one query) and [channels] (the
 * guide's favourite channels, in the guide's order). Recordings and YouTube videos are Movies to
 * Jellyfin but not films, so they stay out of Films.
 */
fun favoriteRows(items: List<BaseItemDto>, channels: List<BaseItemDto>): List<FavoriteRow> {
	val movies = items.filter { it.type == BaseItemKind.MOVIE && !it.isLiveTvRecording && !isFavoriteYouTubeVideo(it) }
	val shows = items.filter { it.type == BaseItemKind.SERIES }
	return listOf(
		FavoriteRow(FavoriteRowKind.MOVIES, movies.distinctById()),
		FavoriteRow(FavoriteRowKind.SHOWS, shows.distinctById()),
		FavoriteRow(FavoriteRowKind.CHANNELS, channels.distinctById()),
	).filter { it.items.isNotEmpty() }
}

/** The ids of every favourite shown, to tell whether a favourite change affects the screen. */
fun favoriteIds(rows: List<FavoriteRow>): Set<String> =
	rows.flatMapTo(HashSet()) { row -> row.items.map { it.id.toString() } }

/**
 * True when a UserDataChanged's [changes] (item id, favourite now) add or remove a favourite
 * the screen doesn't show as such: a play position or a watched mark alone changes nothing.
 */
fun favoritesChanged(shown: Set<String>, changes: List<Pair<String, Boolean>>): Boolean =
	changes.any { (id, favorite) -> favorite != (id in shown) }

/**
 * The card to focus: [lastId] while it is still a favourite, else the card now at [lastIndex] in
 * the row it was in (its neighbour, when it was just removed), else the first card. Null when
 * there are no favourites.
 */
fun favoriteFocusTarget(
	rows: List<FavoriteRow>,
	lastId: String?,
	lastKind: FavoriteRowKind?,
	lastIndex: Int,
): String? {
	if (lastId != null && rows.any { row -> row.items.any { it.id.toString() == lastId } }) return lastId
	val row = rows.firstOrNull { it.kind == lastKind }
	if (row != null) return row.items[lastIndex.coerceIn(0, row.items.size - 1)].id.toString()
	return rows.firstOrNull()?.items?.firstOrNull()?.id?.toString()
}
