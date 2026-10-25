package org.jellyfin.androidtv.ui.livetv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import java.time.LocalDateTime
import java.util.UUID

/** How many recent channels the player's channel row shows. */
const val RECENT_CHANNELS_MAX = 10

/** What the player's channel row shows: the person's recent channels, else their favourites. */
data class RecentChannelsList(val channels: List<BaseItemDto>, val favorites: Boolean)

/**
 * The person's recent channels for the player's channel row, most recent first.
 *
 * Jellyfin keeps when each person last played each channel (UserData.LastPlayedDate, set by the
 * playback start report), so the list is per person and follows them from device to device. A
 * channel tuned on this TV a moment ago may not have reached the server yet: [localPlays] (this
 * app's own tunes) counts too. The channel playing now ([current]) isn't in it: the first card is
 * the one to go back to. Channels never played are left out.
 */
fun recentChannelOrder(
	server: List<BaseItemDto>,
	localPlays: Map<UUID, LocalDateTime>,
	current: UUID?,
	limit: Int = RECENT_CHANNELS_MAX,
): List<BaseItemDto> = server
	.distinctBy { it.id }
	.mapNotNull { channel ->
		val played = listOfNotNull(channel.userData?.lastPlayedDate, localPlays[channel.id]).maxOrNull()
		if (played == null || channel.id == current) null else channel to played
	}
	.sortedByDescending { it.second }
	.take(limit)
	.map { it.first }

/** The recent channels when there are any, else the favourites (minus the one playing). */
fun recentOrFavorites(recents: List<BaseItemDto>, favorites: List<BaseItemDto>, current: UUID?): RecentChannelsList =
	if (recents.isNotEmpty()) RecentChannelsList(recents, favorites = false)
	else RecentChannelsList(favorites.filter { it.id != current }, favorites = true)

/** True when the row on screen differs from [next] (other channels, order, or what's on). */
fun recentChannelsChanged(shown: RecentChannelsList?, next: RecentChannelsList): Boolean {
	if (shown == null || shown.favorites != next.favorites || shown.channels.size != next.channels.size) return true
	return shown.channels.zip(next.channels).any { (a, b) ->
		a.id != b.id || a.currentProgram?.id != b.currentProgram?.id || a.imageTags != b.imageTags || a.name != b.name
	}
}

/**
 * This app's own tunes, kept until the app closes, so the row is right at once after a switch
 * (before the server has the start report). The last list read is kept too, per person, so the
 * row opens at once and is read again behind it.
 */
object RecentChannelsStore {
	private val plays = HashMap<UUID, LocalDateTime>()
	private var lastUser: UUID? = null
	private var lastList: RecentChannelsList? = null

	@JvmStatic
	@Synchronized
	fun notePlayed(channel: UUID, at: LocalDateTime = LocalDateTime.now()) {
		val user = currentLiveTvUserId()
		if (user != lastUser) {
			// Another person: nothing of the last one's is kept
			plays.clear()
			lastList = null
			lastUser = user
		}
		plays[channel] = at
	}

	@Synchronized
	fun localPlays(): Map<UUID, LocalDateTime> = if (currentLiveTvUserId() == lastUser) HashMap(plays) else emptyMap()

	@Synchronized
	fun cached(): RecentChannelsList? = if (currentLiveTvUserId() == lastUser) lastList else null

	@Synchronized
	fun store(list: RecentChannelsList) {
		val user = currentLiveTvUserId()
		if (user != lastUser) {
			plays.clear()
			lastUser = user
		}
		lastList = list
	}
}

/**
 * Reads the row: the channels this person played last (one small request, with what's on now and
 * the logos), else their favourites. Channels Tentacle hides from them never come back from
 * /LiveTv/Channels, so neither list shows one.
 */
suspend fun fetchRecentChannels(api: ApiClient, current: UUID?): RecentChannelsList = withContext(Dispatchers.IO) {
	val played = api.liveTvApi.getLiveTvChannels(
		// One more than shown: the channel playing now is usually the first
		limit = RECENT_CHANNELS_MAX + 1,
		addCurrentProgram = true,
		enableUserData = true,
		enableImageTypes = setOf(ImageType.PRIMARY),
		imageTypeLimit = 1,
		sortBy = setOf(ItemSortBy.DATE_PLAYED),
		sortOrder = SortOrder.DESCENDING,
	).content.items.let(::withoutChannelSeparators)
	val recents = recentChannelOrder(played, RecentChannelsStore.localPlays(), current)
	val list = if (recents.isNotEmpty()) {
		recentOrFavorites(recents, emptyList(), current)
	} else {
		val favorites = api.liveTvApi.getLiveTvChannels(
			isFavorite = true,
			limit = 50,
			addCurrentProgram = true,
			enableUserData = true,
			enableImageTypes = setOf(ImageType.PRIMARY),
			imageTypeLimit = 1,
			sortBy = setOf(ItemSortBy.SORT_NAME),
			sortOrder = SortOrder.ASCENDING,
		).content.items.let(::withoutChannelSeparators)
		recentOrFavorites(emptyList(), favorites, current)
	}
	RecentChannelsStore.store(list)
	list
}
