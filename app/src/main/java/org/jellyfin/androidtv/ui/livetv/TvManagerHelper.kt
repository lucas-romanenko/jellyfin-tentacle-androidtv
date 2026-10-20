package org.jellyfin.androidtv.ui.livetv

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.preference.LiveTvPreferences
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.android.ext.android.inject
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

fun BaseItemDto.copyWithLastPlayedDate(
	lastPlayedDate: LocalDateTime,
) = copy(
	userData = userData?.copy(
		lastPlayedDate = lastPlayedDate,
	)
)

/**
 * The request's value, or null when it failed. A cancelled request rethrows instead: the
 * fragment that asked is gone (Back pressed while the channels were still loading), and
 * its callback must not run on a destroyed fragment (`requireContext()` throws, and a
 * first load's channel list is still null).
 */
internal fun <T> Result<T>.getOrNullUnlessCancelled(): T? = getOrElse {
	if (it is CancellationException) throw it
	null
}

fun loadLiveTvChannels(fragment: Fragment, callback: (channels: Collection<BaseItemDto>?) -> Unit) {
	val liveTvPreferences by fragment.inject<LiveTvPreferences>()
	val api by fragment.inject<ApiClient>()

	fragment.lifecycleScope.launch {
		val sortDatePlayed =
			liveTvPreferences[LiveTvPreferences.channelOrder] == ItemSortBy.DATE_PLAYED.serialName

		runCatching {
			withContext(Dispatchers.IO) {
				api.liveTvApi.getLiveTvChannels(
					addCurrentProgram = true,
					enableFavoriteSorting = liveTvPreferences[LiveTvPreferences.favsAtTop],
					sortBy = if (sortDatePlayed) setOf(ItemSortBy.DATE_PLAYED) else setOf(ItemSortBy.SORT_NAME),
					sortOrder = if (sortDatePlayed) SortOrder.DESCENDING else SortOrder.ASCENDING,
				).content.items
			}
		}.let { callback(it.getOrNullUnlessCancelled()) }
	}
}

fun getPrograms(
	fragment: Fragment,
	channelIds: Array<UUID>,
	startTime: LocalDateTime,
	endTime: LocalDateTime,
	callback: (programs: Collection<BaseItemDto>?) -> Unit,
) {
	val api by fragment.inject<ApiClient>()

	fragment.lifecycleScope.launch {
		runCatching {
			withContext(Dispatchers.IO) {
				// The ids go in the URL, and a server refuses a request line over 8 KB (about 165 ids).
				// A filtered guide asks for every channel, so ask one guide page of channels at a time.
				// Ids not filled yet arrive as nulls from Java: no id asks for every channel, once.
				val ids = channelIds.filterNotNull()
				val batches = if (ids.isEmpty()) listOf(ids) else ids.chunked(LiveTvGuideFragment.PAGE_SIZE)
				batches.flatMap { batch ->
					api.liveTvApi.getLiveTvPrograms(
						channelIds = batch,
						enableImages = false,
						sortBy = setOf(ItemSortBy.START_DATE),
						maxStartDate = endTime,
						minEndDate = startTime,
					).content.items
				}
			}
		}.let { callback(it.getOrNullUnlessCancelled()) }
	}
}

fun getScheduleRows(
	fragment: Fragment,
	seriesTimerId: String?,
	callback: (timers: Map<LocalDate, List<BaseItemDto>>?) -> Unit,
) {
	val api by fragment.inject<ApiClient>()

	fragment.lifecycleScope.launch {
		runCatching {
			withContext(Dispatchers.IO) {
				api.liveTvApi.getTimers(
					seriesTimerId = seriesTimerId,
				).content.items
			}
		}.fold(
			onSuccess = { timers ->
				val groupedTimers = timers
					.filterNot { it.startDate == null }
					.map { timer ->
						timer.programInfo ?: BaseItemDto(
							id = requireNotNull(timer.id?.toUUIDOrNull()),
							channelName = timer.channelName,
							name = timer.name.orEmpty(),
							type = BaseItemKind.PROGRAM,
							mediaType = MediaType.UNKNOWN,
							timerId = timer.id,
							seriesTimerId = timer.seriesTimerId,
							startDate = timer.startDate,
							endDate = timer.endDate,
						)
					}
					.map { it.copy(locationType = LocationType.VIRTUAL) }
					.groupBy { it.startDate!!.toLocalDate() }

				callback(groupedTimers)
			},
			onFailure = {
				if (it is CancellationException) throw it
				callback(null)
			},
		)
	}
}
