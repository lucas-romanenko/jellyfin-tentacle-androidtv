package org.jellyfin.androidtv.ui.search

import org.jellyfin.androidtv.data.repository.DiscoverItem
import org.jellyfin.sdk.model.api.BaseItemDto

/** The Search rows a card can be in; a library item can also be a channel's programme, so the row is part of a card's key. */
enum class SearchCardRow { CHANNELS, LIBRARY }

/** Which card this is, kept across the Search screen being rebuilt (Back from the player or a details page). */
internal fun searchCardKey(row: SearchCardRow, item: BaseItemDto): String = "${row.name.lowercase()}:${item.id}"

internal fun discoverCardKey(item: DiscoverItem): String =
	"discover:${item.mediaType}:${item.tmdbId}:${item.tvdbId}:${item.title}"

/** The keys of every card Search shows now, to tell whether the card opened last is still there. */
internal fun shownSearchCards(
	channels: List<BaseItemDto>,
	library: List<BaseItemDto>,
	discover: List<DiscoverItem>,
): Set<String> = buildSet {
	channels.forEach { add(searchCardKey(SearchCardRow.CHANNELS, it)) }
	library.forEach { add(searchCardKey(SearchCardRow.LIBRARY, it)) }
	discover.forEach { add(discoverCardKey(it)) }
}
