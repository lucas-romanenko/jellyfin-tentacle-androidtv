package org.jellyfin.androidtv.ui.browsing.v2

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.TimerInfoDto
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.time.LocalDateTime

data class ScheduleBrowseUiState(
	val isLoading: Boolean = true,
	val days: List<ScheduleDay> = emptyList(),
)

class ScheduleBrowseViewModel(
	val api: ApiClient,
) : ViewModel() {

	private val _uiState = MutableStateFlow(ScheduleBrowseUiState())
	val uiState: StateFlow<ScheduleBrowseUiState> = _uiState.asStateFlow()

	fun initialize() {
		_uiState.value = ScheduleBrowseUiState(isLoading = true)
		loadSchedule()
	}

	/** Read again without the loading state (a recording changed): the rows stay where they are. */
	fun refresh() = loadSchedule()

	private fun loadSchedule() {
		viewModelScope.launch {
			try {
				val timers = withContext(Dispatchers.IO) {
					api.liveTvApi.getTimers().content
				}
				val days = scheduleDays(timers.items.mapNotNull(::scheduleEntry), LocalDateTime.now())
				_uiState.update { it.copy(isLoading = false, days = days) }
			} catch (err: ApiClientException) {
				Timber.e(err, "Failed to load schedule")
				_uiState.update { it.copy(isLoading = false) }
			}
		}
	}

	/** Cancels one booked programme, or its whole series; the list reads again either way. */
	fun cancel(timerId: String?, seriesTimerId: String?, onDone: (Boolean) -> Unit) {
		viewModelScope.launch {
			val ok = try {
				withContext(Dispatchers.IO) {
					when {
						seriesTimerId != null -> api.liveTvApi.cancelSeriesTimer(seriesTimerId)
						timerId != null -> api.liveTvApi.cancelTimer(timerId)
					}
				}
				true
			} catch (err: ApiClientException) {
				Timber.e(err, "Failed to cancel timer=$timerId series=$seriesTimerId")
				false
			}
			onDone(ok)
			refresh()
		}
	}
}

/**
 * A timer as a Schedule row. Jellyfin sends a timer's programme only while it is in the guide: a
 * timer without one gets a stand-in programme that OK can still open.
 */
internal fun scheduleEntry(timer: TimerInfoDto): ScheduleEntry? {
	val start = timer.startDate ?: return null
	val id = timer.id?.toUUIDOrNull() ?: timer.programInfo?.id ?: return null
	val programme = (timer.programInfo ?: BaseItemDto(
		id = id,
		channelName = timer.channelName,
		name = timer.name.orEmpty(),
		type = BaseItemKind.PROGRAM,
		mediaType = MediaType.UNKNOWN,
		timerId = timer.id,
		seriesTimerId = timer.seriesTimerId,
		startDate = timer.startDate,
		endDate = timer.endDate,
	)).copy(locationType = LocationType.VIRTUAL)
	return ScheduleEntry(
		item = programme,
		timerId = timer.id,
		seriesTimerId = timer.seriesTimerId?.takeIf { it.isNotBlank() },
		name = timer.name?.takeIf { it.isNotBlank() } ?: programme.name.orEmpty(),
		episodeTitle = programme.episodeTitle?.takeIf { it.isNotBlank() },
		channelName = timer.channelName?.takeIf { it.isNotBlank() } ?: programme.channelName,
		start = start,
		end = timer.endDate,
		status = timer.status,
	)
}
