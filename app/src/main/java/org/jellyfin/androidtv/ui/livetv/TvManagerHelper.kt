package org.jellyfin.androidtv.ui.livetv

import androidx.fragment.app.Fragment
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.preference.LiveTvPreferences
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.displayPreferencesApi
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.model.api.GetProgramsDto
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.android.ext.android.inject
import org.koin.java.KoinJavaComponent
import timber.log.Timber
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
		runCatching { fetchAllGuideChannels(api, liveTvPreferences) }
			.let { callback(it.getOrNullUnlessCancelled()) }
	}
}

/** Every channel this person can see, in the guide's order (the All view; one request). */
suspend fun fetchAllGuideChannels(api: ApiClient, liveTvPreferences: LiveTvPreferences): List<BaseItemDto> =
	fetchGuideChannelPage(api, liveTvPreferences, startIndex = null, limit = null).channels

/** One page of the All view's channels, in the same order as [fetchAllGuideChannels], and how many there are in all. */
class GuideChannelPage(val channels: List<BaseItemDto>, val total: Int?)

suspend fun fetchGuideChannelPage(
	api: ApiClient,
	liveTvPreferences: LiveTvPreferences,
	startIndex: Int?,
	limit: Int?,
): GuideChannelPage {
	val sortDatePlayed = liveTvPreferences[LiveTvPreferences.channelOrder] == ItemSortBy.DATE_PLAYED.serialName
	return withContext(Dispatchers.IO) {
		val result = api.liveTvApi.getLiveTvChannels(
			startIndex = startIndex,
			limit = limit,
			addCurrentProgram = true,
			enableFavoriteSorting = liveTvPreferences[LiveTvPreferences.favsAtTop],
			sortBy = if (sortDatePlayed) setOf(ItemSortBy.DATE_PLAYED) else setOf(ItemSortBy.SORT_NAME),
			sortOrder = if (sortDatePlayed) SortOrder.DESCENDING else SortOrder.ASCENDING,
		).content
		GuideChannelPage(withoutChannelSeparators(result.items), result.totalRecordCount)
	}
}

/** How many favourite channels this person has (one cheap count, not the list). */
suspend fun fetchFavoriteChannelCount(api: ApiClient): Int? = withContext(Dispatchers.IO) {
	api.liveTvApi.getLiveTvChannels(
		isFavorite = true,
		limit = 1,
		addCurrentProgram = false,
		enableImages = false,
		enableUserData = false,
	).content.totalRecordCount
}

/**
 * The All view's first screen: its first [pageSize] channels, how many channels there are and how
 * many are favourites (three small requests at once), so the guide shows before the whole list
 * (1,700 channels, a megabyte to read on a TV) has come. Null when it failed.
 */
fun loadFirstGuidePage(
	fragment: Fragment,
	pageSize: Int,
	callback: (page: List<BaseItemDto>?, allCount: Int?, favoriteCount: Int?) -> Unit,
) {
	val liveTvPreferences by fragment.inject<LiveTvPreferences>()
	val api by fragment.inject<ApiClient>()

	fragment.lifecycleScope.launch {
		val counts = async { runCatching { fetchFavoriteChannelCount(api) }.getOrNullUnlessCancelled() }
		val page = runCatching { fetchGuideChannelPage(api, liveTvPreferences, startIndex = 0, limit = pageSize) }.getOrNullUnlessCancelled()
		callback(page?.channels, page?.total, counts.await())
	}
}

/** The whole All list, read after the first screen shows (cancelled with the guide). Null when it failed. */
fun loadAllGuideChannels(fragment: Fragment, callback: (channels: List<BaseItemDto>?) -> Unit) {
	val liveTvPreferences by fragment.inject<LiveTvPreferences>()
	val api by fragment.inject<ApiClient>()

	fragment.lifecycleScope.launch {
		runCatching { fetchAllGuideChannels(api, liveTvPreferences) }
			.let { callback(it.getOrNullUnlessCancelled()) }
	}
}

/** Who is signed in: the stored channel list is theirs only (null when nobody is, so nothing stored is shown). */
fun currentLiveTvUserId(): UUID? =
	KoinJavaComponent.getOrNull<SessionRepository>(SessionRepository::class.java)?.currentSession?.value?.userId

/** This person's favourite channels only, in the guide's order (the Favorites view). */
suspend fun fetchFavoriteGuideChannels(api: ApiClient, liveTvPreferences: LiveTvPreferences): List<BaseItemDto> {
	val sortDatePlayed = liveTvPreferences[LiveTvPreferences.channelOrder] == ItemSortBy.DATE_PLAYED.serialName
	return withContext(Dispatchers.IO) {
		api.liveTvApi.getLiveTvChannels(
			isFavorite = true,
			addCurrentProgram = false,
			enableUserData = true,
			sortBy = if (sortDatePlayed) setOf(ItemSortBy.DATE_PLAYED) else setOf(ItemSortBy.SORT_NAME),
			sortOrder = if (sortDatePlayed) SortOrder.DESCENDING else SortOrder.ASCENDING,
		).content.items.let(::withoutChannelSeparators)
	}
}

/** How many channels this person can see in all (one cheap count, not the whole list). */
suspend fun fetchGuideChannelCount(api: ApiClient): Int? = withContext(Dispatchers.IO) {
	api.liveTvApi.getLiveTvChannels(
		limit = 1,
		addCurrentProgram = false,
		enableImages = false,
		enableUserData = false,
	).content.totalRecordCount
}

/**
 * This person's favourite channels only (Jellyfin favourites are per user), in the guide's order,
 * plus how many channels they can see in all (one cheap count, not the whole list).
 */
fun loadFavoriteLiveTvChannels(
	fragment: Fragment,
	callback: (channels: Collection<BaseItemDto>?, allCount: Int?) -> Unit,
) {
	val liveTvPreferences by fragment.inject<LiveTvPreferences>()
	val api by fragment.inject<ApiClient>()

	fragment.lifecycleScope.launch {
		val favorites = runCatching { fetchFavoriteGuideChannels(api, liveTvPreferences) }.getOrNullUnlessCancelled()
		val allCount = runCatching { fetchGuideChannelCount(api) }.getOrNullUnlessCancelled()
		callback(favorites, allCount)
	}
}

/**
 * When the guide data ends: the end of the furthest programme ([channelIds] only, or every
 * channel when null), for paging, and where the days with real guide data end, for the date
 * choice ([fetchGuideDaysEnd]). Null when unknown.
 */
fun loadFurthestProgramEnd(
	fragment: Fragment,
	channelIds: List<UUID>?,
	callback: (end: LocalDateTime?, daysEnd: LocalDateTime?) -> Unit,
) {
	val api by fragment.inject<ApiClient>()

	fragment.lifecycleScope.launch {
		val end = runCatching { fetchFurthestProgramEnd(api, channelIds) }.getOrNullUnlessCancelled()
		val daysEnd = runCatching { fetchGuideDaysEnd(api, channelIds, end) }.getOrNullUnlessCancelled()
		callback(end, daysEnd ?: end)
	}
}

/** POST /LiveTv/Programs for the last programme in the guide (ids in the body: no URL limit). */
internal fun furthestProgramRequest(channelIds: List<UUID>?) = GetProgramsDto(
	channelIds = channelIds,
	sortBy = listOf(ItemSortBy.START_DATE),
	sortOrder = listOf(SortOrder.DESCENDING),
	limit = 1,
	enableImages = false,
	enableUserData = false,
	enableTotalRecordCount = false,
)

/** The person's saved Favorites / All choice (shared with Tentacle's web guide); Favorites when unknown. */
fun loadGuideView(fragment: Fragment, callback: (view: GuideView) -> Unit) {
	val api by fragment.inject<ApiClient>()

	fragment.lifecycleScope.launch {
		val prefs = runCatching {
			withTimeoutOrNull(3_000) {
				withContext(Dispatchers.IO) {
					api.displayPreferencesApi.getDisplayPreferences(
						displayPreferencesId = GuideView.PREFS_ID,
						client = GuideView.PREFS_CLIENT,
					).content
				}
			}
		}.getOrNullUnlessCancelled()
		callback(GuideView.fromPrefs(prefs?.customPrefs))
	}
}

/** Saves the Favorites / All choice, keeping whatever else the web keeps in the same preferences. */
fun saveGuideView(fragment: Fragment, view: GuideView) {
	val client by fragment.inject<ApiClient>()
	val api = client // resolved now, while the fragment is still attached

	// Not the fragment's scope: Back right after the toggle must not cancel the save.
	ProcessLifecycleOwner.get().lifecycleScope.launch {
		runCatching {
			withContext(Dispatchers.IO) {
				val current = runCatching {
					api.displayPreferencesApi.getDisplayPreferences(
						displayPreferencesId = GuideView.PREFS_ID,
						client = GuideView.PREFS_CLIENT,
					).content
				}.getOrNull()
				api.displayPreferencesApi.updateDisplayPreferences(
					displayPreferencesId = GuideView.PREFS_ID,
					client = GuideView.PREFS_CLIENT,
					data = withGuideView(current, view),
				)
			}
		}.onFailure { Timber.w(it, "Unable to save the guide view") }
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
			// Ids not filled yet arrive as nulls from Java: no id asks for every channel, once.
			fetchGuidePrograms(api, channelIds.filterNotNull(), startTime, endTime)
		}.let { callback(it.getOrNullUnlessCancelled()) }
	}
}

/** Programmes of [ids] between [startTime] and [endTime]; no id asks for every channel, once. */
suspend fun fetchGuidePrograms(
	api: ApiClient,
	ids: List<UUID>,
	startTime: LocalDateTime,
	endTime: LocalDateTime,
): List<BaseItemDto> = withContext(Dispatchers.IO) {
	// The ids go in the URL, and a server refuses a request line over 8 KB (about 165 ids).
	// A filtered guide asks for every channel, so ask one guide page of channels at a time.
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

/** The end of the furthest programme of [channelIds] (every channel when null); null when unknown. */
suspend fun fetchFurthestProgramEnd(api: ApiClient, channelIds: List<UUID>?): LocalDateTime? = withContext(Dispatchers.IO) {
	api.liveTvApi.getPrograms(furthestProgramRequest(channelIds)).content.items.firstOrNull()?.endDate
}

/** POST /LiveTv/Programs for how many programmes of [channelIds] are on during [day] (count only). */
internal fun dayProgramCountRequest(channelIds: List<UUID>?, day: LocalDate) = GetProgramsDto(
	channelIds = channelIds,
	minEndDate = day.atStartOfDay(),
	maxStartDate = day.plusDays(1).atStartOfDay(),
	limit = 0,
	enableImages = false,
	enableUserData = false,
	enableTotalRecordCount = true,
)

/**
 * Where the days with real guide data end (the start of the day after the last one), so one
 * long event listing far ahead can't add empty days to the date choice: the days are counted
 * one by one from today and stop at the first with too few programmes ([realGuideDayCount]).
 * [furthestEnd] when every day up to it has real data.
 */
suspend fun fetchGuideDaysEnd(api: ApiClient, channelIds: List<UUID>?, furthestEnd: LocalDateTime?): LocalDateTime? = withContext(Dispatchers.IO) {
	benchGuideDays()?.let { return@withContext LocalDate.now().plusDays(it.toLong()).atStartOfDay() }
	val days = guideDays(LocalDateTime.now(), furthestEnd)
	if (days.size < 2) return@withContext furthestEnd
	val counts = mutableListOf<Int>()
	for (day in days) {
		counts += api.liveTvApi.getPrograms(dayProgramCountRequest(channelIds, day)).content.totalRecordCount ?: 0
		if (realGuideDayCount(counts) < counts.size) break
	}
	val kept = realGuideDayCount(counts)
	if (kept >= days.size) furthestEnd else days[kept - 1].plusDays(1).atStartOfDay()
}

/**
 * Bench and debug builds only: `adb shell setprop debug.tentacle.guidedays N` offers N days
 * whatever the data says, to try a long run of dates on a TV whose provider sends two. Null otherwise.
 */
@android.annotation.SuppressLint("PrivateApi")
private fun benchGuideDays(): Int? {
	if (BuildConfig.BUILD_TYPE == "release") return null
	return runCatching {
		Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
			.invoke(null, "debug.tentacle.guidedays") as String
	}.getOrNull()?.toIntOrNull()?.takeIf { it in 1..MAX_GUIDE_DAYS }
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
