package org.jellyfin.androidtv.ui.livetv

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.repository.ItemMutationRepository
import org.jellyfin.androidtv.ui.GuideChannelHeader
import org.jellyfin.androidtv.ui.ProgramGridCell
import org.jellyfin.androidtv.ui.asTimerInfoDto
import org.jellyfin.androidtv.ui.copyWithSeriesTimerId
import org.jellyfin.androidtv.ui.copyWithTimerId
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.rethrowIfCancelled
import org.jellyfin.androidtv.ui.shared.menu.TvMenuAction
import org.jellyfin.androidtv.ui.shared.menu.TvMenus
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.time.Instant
import java.time.LocalDateTime

/** A choice of a guide programme's menu, in the order shown. */
enum class GuideProgrammeChoice {
	/** On now: play the channel. */
	WATCH_NOW,

	/** Not on now (later, or over): play what the channel shows now. */
	TUNE_TO_CHANNEL,
	RECORD,
	CANCEL_RECORDING,
	RECORD_SERIES,
	CANCEL_SERIES,
	DETAILS,
}

/**
 * A guide programme's menu (a long press of OK, the Menu key, or OK on a programme that hasn't
 * started): Watch now or Tune to channel, Record this / Record every episode (their cancels when
 * already booked) for people who may manage recordings while it hasn't ended, then Details. No
 * Favorite: a star here read as starring the show, and it starred the channel (Lucas, 24 Oct); a
 * channel is starred on its name in the left column.
 */
fun guideProgrammeChoices(
	start: LocalDateTime?,
	end: LocalDateTime?,
	now: LocalDateTime,
	canManage: Boolean,
	timerId: String?,
	seriesTimerId: String?,
	isSeries: Boolean,
): List<GuideProgrammeChoice> = buildList {
	val onNow = start != null && end != null && !start.isAfter(now) && end.isAfter(now)
	val ended = end != null && !end.isAfter(now)
	add(if (onNow) GuideProgrammeChoice.WATCH_NOW else GuideProgrammeChoice.TUNE_TO_CHANNEL)
	if (canManage && !ended) {
		add(if (timerId != null) GuideProgrammeChoice.CANCEL_RECORDING else GuideProgrammeChoice.RECORD)
		if (isSeries || seriesTimerId != null) {
			add(if (seriesTimerId != null) GuideProgrammeChoice.CANCEL_SERIES else GuideProgrammeChoice.RECORD_SERIES)
		}
	}
	add(GuideProgrammeChoice.DETAILS)
}

/** The focused programme's menu ([guideProgrammeChoices]), in the app's one menu style. */
fun LiveTvGuideFragment.showProgramMenu() {
	val cell = mSelectedProgramView as? ProgramGridCell ?: return
	// The cell holds the programme as last changed (Record / Cancel, the live timer marks), while
	// mSelectedProgram may still be the copy from when the guide loaded.
	val program = cell.program ?: mSelectedProgram ?: return
	mSelectedProgram = program
	val channelId = program.channelId ?: return

	val userRepository by inject<UserRepository>()
	val playbackHelper by inject<PlaybackHelper>()
	val navigationRepository by inject<NavigationRepository>()
	val context = requireContext()

	val choices = guideProgrammeChoices(
		start = program.startDate,
		end = program.endDate,
		now = LocalDateTime.now(),
		canManage = Utils.canManageRecordings(userRepository.currentUser.value),
		timerId = program.timerId,
		seriesTimerId = program.seriesTimerId,
		isSeries = program.isSeries == true,
	)
	val actions = choices.map { choice ->
		when (choice) {
			GuideProgrammeChoice.WATCH_NOW -> TvMenuAction(context.getString(R.string.lbl_watch_now)) {
				playbackHelper.retrieveAndPlay(channelId, false, context)
			}
			GuideProgrammeChoice.TUNE_TO_CHANNEL -> TvMenuAction(context.getString(R.string.lbl_tune_to_channel)) {
				playbackHelper.retrieveAndPlay(channelId, false, context)
			}
			GuideProgrammeChoice.RECORD -> TvMenuAction(context.getString(R.string.lbl_record_this)) {
				recordFromGuide(cell, program, series = false)
			}
			GuideProgrammeChoice.RECORD_SERIES -> TvMenuAction(context.getString(R.string.lbl_record_every_episode)) {
				recordFromGuide(cell, program, series = true)
			}
			GuideProgrammeChoice.CANCEL_RECORDING -> TvMenuAction(context.getString(R.string.lbl_cancel_this_recording)) {
				confirmCancelFromGuide(cell, program)
			}
			GuideProgrammeChoice.CANCEL_SERIES -> TvMenuAction(
				label = context.getString(R.string.lbl_cancel_series_ellipsis),
				hint = context.getString(R.string.hint_cancel_series),
			) { confirmCancelSeriesFromGuide(cell, program) }
			GuideProgrammeChoice.DETAILS -> TvMenuAction(context.getString(R.string.lbl_details)) {
				navigationRepository.navigate(Destinations.channelDetails(program.id, channelId, program))
			}
		}
	}
	TvMenus.show(requireActivity(), guideMenuTitle(program), actions)
}

/** "Hockey Night · 7:00 PM": the programme and when, so the menu says what it is about. */
private fun LiveTvGuideFragment.guideMenuTitle(program: BaseItemDto): String {
	val start = program.startDate ?: return program.name.orEmpty()
	val time = android.text.format.DateFormat.getTimeFormat(requireContext())
		.format(java.util.Date.from(start.atZone(java.time.ZoneId.systemDefault()).toInstant()))
	return listOfNotNull(program.name, time).joinToString(" · ")
}

private fun LiveTvGuideFragment.showGuideRequestFailure(error: Throwable, @StringRes message: Int) {
	// The guide closed while this ran: there is nothing to tell
	error.rethrowIfCancelled()
	Timber.e(error, "Live TV recording request failed")
	context?.let { Toast.makeText(it, it.getString(message), Toast.LENGTH_LONG).show() }
}

/** The cell (and the header above the grid) show [program] as it is now. */
private fun LiveTvGuideFragment.showProgramChanged(cell: ProgramGridCell, program: BaseItemDto) {
	cell.setProgram(program)
	cell.setRecSeriesTimer(program.seriesTimerId)
	cell.setRecTimer(program.timerId)
	if (mSelectedProgramView === cell) mSelectedProgram = program
}

private fun LiveTvGuideFragment.recordFromGuide(cell: ProgramGridCell, program: BaseItemDto, series: Boolean) {
	val api by inject<ApiClient>()
	lifecycleScope.launch {
		runCatching {
			withContext(Dispatchers.IO) {
				val defaults by api.liveTvApi.getDefaultTimer(program.id.toString())
				if (series) api.liveTvApi.createSeriesTimer(defaults)
				else api.liveTvApi.createTimer(defaults.asTimerInfoDto())
				api.liveTvApi.getProgram(program.id.toString()).content
			}
		}.onSuccess { updated ->
			showProgramChanged(cell, updated)
			context?.let { Toast.makeText(it, R.string.msg_set_to_record, Toast.LENGTH_SHORT).show() }
		}.onFailure { error ->
			showGuideRequestFailure(error, R.string.msg_unable_to_create_recording)
		}
	}
}

private fun LiveTvGuideFragment.confirmCancelFromGuide(cell: ProgramGridCell, program: BaseItemDto) {
	val timerId = program.timerId ?: return
	val recordingNow = program.startDate?.isAfter(LocalDateTime.now()) == false
	TvMenus.confirm(
		activity = requireActivity(),
		message = getString(if (recordingNow) R.string.msg_cancel_recording_in_progress else R.string.msg_cancel_recording),
		confirmLabel = getString(R.string.lbl_cancel_recording),
		cancelLabel = getString(R.string.lbl_keep_it),
	) {
		val api by inject<ApiClient>()
		lifecycleScope.launch {
			runCatching {
				withContext(Dispatchers.IO) { api.liveTvApi.cancelTimer(timerId) }
			}.onSuccess {
				showProgramChanged(cell, program.copyWithTimerId(null))
				context?.let { Toast.makeText(it, R.string.msg_recording_cancelled, Toast.LENGTH_SHORT).show() }
			}.onFailure { error ->
				showGuideRequestFailure(error, R.string.msg_unable_to_cancel)
			}
		}
	}
}

private fun LiveTvGuideFragment.confirmCancelSeriesFromGuide(cell: ProgramGridCell, program: BaseItemDto) {
	val seriesTimerId = program.seriesTimerId ?: return
	TvMenus.confirm(
		activity = requireActivity(),
		message = getString(R.string.msg_cancel_entire_series),
		confirmLabel = getString(R.string.lbl_cancel_series),
		cancelLabel = getString(R.string.lbl_keep_it),
	) {
		val api by inject<ApiClient>()
		lifecycleScope.launch {
			runCatching {
				withContext(Dispatchers.IO) { api.liveTvApi.cancelSeriesTimer(seriesTimerId) }
			}.onSuccess {
				showProgramChanged(cell, program.copyWithSeriesTimerId(null))
				context?.let { Toast.makeText(it, R.string.msg_recording_cancelled, Toast.LENGTH_SHORT).show() }
			}.onFailure { error ->
				showGuideRequestFailure(error, R.string.msg_unable_to_cancel)
			}
		}
	}
}

/** What a long press on a channel's name does: star it at once, or ask before taking the star off. */
enum class GuideFavoriteStep { ADD, CONFIRM_REMOVE }

fun guideFavoriteStep(isFavorite: Boolean): GuideFavoriteStep =
	if (isFavorite) GuideFavoriteStep.CONFIRM_REMOVE else GuideFavoriteStep.ADD

/**
 * A long press of OK on a channel's name (left column): not a favourite yet → starred now, with a
 * small "Added to Favorites"; a favourite → "Remove <channel> from your Favorites?" first (Lucas,
 * 24 Oct: one held press took the star off before).
 */
fun LiveTvGuideFragment.favoriteFromGuide() {
	val header = mSelectedProgramView as? GuideChannelHeader ?: return
	val channel = header.channel
	when (guideFavoriteStep(channel.userData?.isFavorite == true)) {
		GuideFavoriteStep.ADD -> setChannelFavorite(header, true)
		GuideFavoriteStep.CONFIRM_REMOVE -> TvMenus.confirm(
			activity = requireActivity(),
			message = getString(R.string.msg_remove_channel_favorite, channel.name.orEmpty()),
			confirmLabel = getString(R.string.lbl_remove),
			cancelLabel = getString(R.string.lbl_cancel),
		) { setChannelFavorite(header, false) }
	}
}

private fun LiveTvGuideFragment.setChannelFavorite(header: GuideChannelHeader, favorite: Boolean) {
	val itemMutationRepository by inject<ItemMutationRepository>()
	val dataRefreshService by inject<DataRefreshService>()

	lifecycleScope.launch {
		runCatching {
			itemMutationRepository.setFavorite(item = header.channel.id, favorite = favorite)
		}.onSuccess { userData ->
			val updated = header.channel.copy(userData = userData)
			header.channel = updated
			header.findViewById<android.view.View>(R.id.favImage).isVisible = userData.isFavorite
			// The stored copy too: the Favorites count and view read it
			TvManager.updateChannel(updated)
			onFavoritesChanged()
			dataRefreshService.lastFavoriteUpdate = Instant.now()
			context?.let {
				Toast.makeText(
					it,
					if (userData.isFavorite) R.string.msg_added_to_favorites else R.string.msg_removed_from_favorites,
					Toast.LENGTH_SHORT,
				).show()
			}
		}.onFailure { error ->
			error.rethrowIfCancelled()
			Timber.e(error, "Unable to change the channel's favourite")
			context?.let { Toast.makeText(it, R.string.msg_unable_to_update, Toast.LENGTH_LONG).show() }
		}
	}
}
