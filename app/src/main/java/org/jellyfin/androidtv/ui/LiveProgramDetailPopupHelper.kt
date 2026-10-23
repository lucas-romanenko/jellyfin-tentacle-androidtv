package org.jellyfin.androidtv.ui

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.lifecycle.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.repository.ItemMutationRepository
import org.jellyfin.androidtv.ui.playback.rethrowIfCancelled
import org.jellyfin.androidtv.util.getActivity
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.SeriesTimerInfoDto
import org.jellyfin.sdk.model.api.TimerInfoDto
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.UUID

fun BaseItemDto.copyWithTimerId(
	timerId: String?,
) = copy(
	timerId = timerId,
)

fun BaseItemDto.copyWithSeriesTimerId(
	seriesTimerId: String?,
) = copy(
	seriesTimerId = seriesTimerId,
)

/** Jellyfin refused or didn't answer a Record or Cancel: say so, or the press looks lost (#197). */
private fun LiveProgramDetailPopup.showRequestFailure(error: Throwable, @StringRes message: Int) {
	// The popup closed while this ran: there is nothing to tell (#124 class)
	error.rethrowIfCancelled()
	Timber.e(error, "Live TV recording request failed")
	Toast.makeText(mContext, mContext.getString(message), Toast.LENGTH_LONG).show()
}

fun LiveProgramDetailPopup.cancelTimer(
	timerId: String,
	callback: () -> Unit,
) {
	val api by mContext.getActivity()!!.inject<ApiClient>()

	lifecycle.coroutineScope.launch {
		runCatching {
			withContext(Dispatchers.IO) {
				api.liveTvApi.cancelTimer(timerId)
			}
		}.onSuccess {
			callback()
		}.onFailure { error ->
			showRequestFailure(error, R.string.msg_unable_to_cancel)
		}
	}
}

fun LiveProgramDetailPopup.cancelSeriesTimer(
	seriesTimerId: String,
	callback: () -> Unit,
) {
	val api by mContext.getActivity()!!.inject<ApiClient>()

	lifecycle.coroutineScope.launch {
		runCatching {
			withContext(Dispatchers.IO) {
				api.liveTvApi.cancelSeriesTimer(seriesTimerId)
			}
		}.onSuccess {
			callback()
		}.onFailure { error ->
			showRequestFailure(error, R.string.msg_unable_to_cancel)
		}
	}
}

fun SeriesTimerInfoDto.asTimerInfoDto() = TimerInfoDto(
	id = id,
	type = type,
	serverId = serverId,
	externalId = externalId,
	channelId = channelId,
	externalChannelId = externalChannelId,
	channelName = channelName,
	channelPrimaryImageTag = channelPrimaryImageTag,
	programId = programId,
	externalProgramId = externalProgramId,
	name = name,
	overview = overview,
	startDate = startDate,
	endDate = endDate,
	serviceName = serviceName,
	priority = priority,
	prePaddingSeconds = prePaddingSeconds,
	postPaddingSeconds = postPaddingSeconds,
	isPrePaddingRequired = isPrePaddingRequired,
	parentBackdropItemId = parentBackdropItemId,
	parentBackdropImageTags = parentBackdropImageTags,
	isPostPaddingRequired = isPostPaddingRequired,
	keepUntil = keepUntil,
)

fun LiveProgramDetailPopup.recordProgram(
	programId: UUID,
	callback: (program: BaseItemDto) -> Unit,
) {
	val api by mContext.getActivity()!!.inject<ApiClient>()

	lifecycle.coroutineScope.launch {
		runCatching {
			withContext(Dispatchers.IO) {
				val seriesTimer by api.liveTvApi.getDefaultTimer(programId.toString())
				val timer = seriesTimer.asTimerInfoDto()
				api.liveTvApi.createTimer(timer)
				api.liveTvApi.getProgram(programId.toString()).content
			}
		}.onSuccess { program ->
			callback(program)
		}.onFailure { error ->
			showRequestFailure(error, R.string.msg_unable_to_create_recording)
		}
	}
}

fun LiveProgramDetailPopup.recordSeries(
	programId: UUID,
	callback: (program: BaseItemDto) -> Unit,
) {
	val api by mContext.getActivity()!!.inject<ApiClient>()

	lifecycle.coroutineScope.launch {
		runCatching {
			withContext(Dispatchers.IO) {
				val timer by api.liveTvApi.getDefaultTimer(programId.toString())
				api.liveTvApi.createSeriesTimer(timer)
				api.liveTvApi.getProgram(programId.toString()).content
			}
		}.onSuccess { program ->
			callback(program)
		}.onFailure { error ->
			showRequestFailure(error, R.string.msg_unable_to_create_recording)
		}
	}
}

fun LiveProgramDetailPopup.getSeriesTimer(
	seriesTimerId: String,
	callback: (seriesTimer: SeriesTimerInfoDto) -> Unit,
) {
	val api by mContext.getActivity()!!.inject<ApiClient>()

	lifecycle.coroutineScope.launch {
		runCatching {
			withContext(Dispatchers.IO) {
				api.liveTvApi.getSeriesTimer(seriesTimerId).content
			}
		}.onSuccess { seriesTimer ->
			callback(seriesTimer)
		}
	}
}

fun LiveProgramDetailPopup.toggleFavorite(
	item: BaseItemDto,
	callback: (item: BaseItemDto) -> Unit,
) {
	val itemMutationRepository by mContext.getActivity()!!.inject<ItemMutationRepository>()

	lifecycle.coroutineScope.launch {
		runCatching {
			val userData = itemMutationRepository.setFavorite(
				item = item.id,
				favorite = !(item.userData?.isFavorite ?: false)
			)

			item.copy(userData = userData)
		}.onSuccess { item ->
			callback(item)
		}
	}
}
