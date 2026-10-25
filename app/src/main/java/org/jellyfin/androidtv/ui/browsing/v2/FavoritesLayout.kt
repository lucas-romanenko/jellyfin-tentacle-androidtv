package org.jellyfin.androidtv.ui.browsing.v2

import org.jellyfin.androidtv.ui.playback.segment.youtubeVideoId
import org.jellyfin.androidtv.util.sdk.isLiveTvRecording
import org.jellyfin.androidtv.util.sdk.youTubeChannelFromPath
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * The Favourites screen's rows, in the order of Tentacle web's Favourites (Movies, Shows, Live TV,
 * YouTube, Recordings, then the rest), so the person's favourite channels are the third row. A row
 * with nothing in it isn't shown.
 */
enum class FavoriteRowKind(val wide: Boolean) {
	MOVIES(wide = false),
	SHOWS(wide = false),
	CHANNELS(wide = true),
	YOUTUBE(wide = true),
	RECORDINGS(wide = true),
	SEASONS(wide = false),
	EPISODES(wide = true),
	COLLECTIONS(wide = false),
	PLAYLISTS(wide = false),
	VIDEOS(wide = true),
	PEOPLE(wide = false),
}

data class FavoriteRow(val kind: FavoriteRowKind, val items: List<BaseItemDto>)

/** Tentacle's YouTube live channels carry this tag prefix (followed by the person's id). */
private const val YOUTUBE_CHANNEL_TAG = "tentacle-youtube-"

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

/** True for one of Tentacle's YouTube live channels: it goes in the YouTube row, not Live TV. */
fun isTentacleYouTubeChannel(channel: BaseItemDto): Boolean =
	channel.tags.orEmpty().any { it.startsWith(YOUTUBE_CHANNEL_TAG, ignoreCase = true) }

/**
 * Sorts the favourites into rows: [items] (one query for every kind), [channels] (the guide's
 * favourite channels, in the guide's order) and [people]. Recordings and YouTube videos are
 * Movies or Videos to Jellyfin, so they are told apart first.
 */
fun favoriteRows(
	items: List<BaseItemDto>,
	channels: List<BaseItemDto>,
	people: List<BaseItemDto>,
): List<FavoriteRow> {
	val byKind = FavoriteRowKind.entries.associateWith { mutableListOf<BaseItemDto>() }
	val (youTubeChannels, liveChannels) = channels.partition(::isTentacleYouTubeChannel)
	byKind.getValue(FavoriteRowKind.CHANNELS) += liveChannels

	for (item in items) {
		val kind = when {
			item.isLiveTvRecording -> FavoriteRowKind.RECORDINGS
			isFavoriteYouTubeVideo(item) -> FavoriteRowKind.YOUTUBE
			else -> when (item.type) {
				BaseItemKind.MOVIE -> FavoriteRowKind.MOVIES
				BaseItemKind.SERIES -> FavoriteRowKind.SHOWS
				BaseItemKind.SEASON -> FavoriteRowKind.SEASONS
				BaseItemKind.EPISODE -> FavoriteRowKind.EPISODES
				BaseItemKind.BOX_SET -> FavoriteRowKind.COLLECTIONS
				BaseItemKind.PLAYLIST -> FavoriteRowKind.PLAYLISTS
				BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO -> FavoriteRowKind.VIDEOS
				else -> null
			}
		} ?: continue
		byKind.getValue(kind) += item
	}
	// Videos first, then the YouTube live channels, as on the web
	byKind.getValue(FavoriteRowKind.YOUTUBE) += youTubeChannels
	byKind.getValue(FavoriteRowKind.PEOPLE) += people

	return FavoriteRowKind.entries
		.map { FavoriteRow(it, byKind.getValue(it).distinctById()) }
		.filter { it.items.isNotEmpty() }
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
