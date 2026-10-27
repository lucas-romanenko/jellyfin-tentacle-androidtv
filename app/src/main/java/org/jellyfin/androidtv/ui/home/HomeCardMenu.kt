package org.jellyfin.androidtv.ui.home

import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.leanback.widget.ListRow
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.data.repository.ItemMutationRepository
import org.jellyfin.androidtv.ui.itemhandling.BaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemRowAdapter
import org.jellyfin.androidtv.ui.itemhandling.refreshItem
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher
import org.jellyfin.androidtv.ui.playback.rethrowIfCancelled
import org.jellyfin.androidtv.ui.shared.menu.TvMenuAction
import org.jellyfin.androidtv.ui.shared.menu.TvMenus
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.androidtv.util.apiclient.Response
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.android.ext.android.inject
import timber.log.Timber

/** A choice of a Home card's menu, in the order shown. */
enum class HomeCardChoice {
	REMOVE_FROM_CONTINUE_WATCHING,
	PLAY_FROM_START,
	MARK_WATCHED,
	MARK_UNWATCHED,
	DETAILS,
}

/** Cards that can be watched (a menu with Mark watched); channels, programmes, folders and people have none. */
fun homeCardHasMenu(type: BaseItemKind?): Boolean = when (type) {
	BaseItemKind.MOVIE,
	BaseItemKind.EPISODE,
	BaseItemKind.SERIES,
	BaseItemKind.SEASON,
	BaseItemKind.VIDEO,
	BaseItemKind.MUSIC_VIDEO,
	BaseItemKind.BOX_SET,
	BaseItemKind.RECORDING,
	BaseItemKind.TRAILER -> true
	else -> false
}

/**
 * A long press of OK (or Menu) on a Home card. In Continue Watching, on a title with a saved place:
 * Remove from Continue Watching, Play from start, Mark watched, Details (Lucas, 24 Oct: "some of them
 * I'm not going to continue watching"). Any other card (a Next Up episode in a merged row too):
 * Mark watched / unwatched, Details.
 */
fun homeCardChoices(inContinueWatching: Boolean, hasPosition: Boolean, played: Boolean): List<HomeCardChoice> =
	if (inContinueWatching && hasPosition) listOf(
		HomeCardChoice.REMOVE_FROM_CONTINUE_WATCHING,
		HomeCardChoice.PLAY_FROM_START,
		HomeCardChoice.MARK_WATCHED,
		HomeCardChoice.DETAILS,
	) else listOf(
		if (played) HomeCardChoice.MARK_UNWATCHED else HomeCardChoice.MARK_WATCHED,
		HomeCardChoice.DETAILS,
	)

/** Jellyfin's Continue Watching rows (on their own, or merged with Next Up). */
fun isContinueWatchingQuery(queryType: QueryType?): Boolean =
	queryType == QueryType.Resume || queryType == QueryType.MergedContinueWatching

/**
 * The card menu for [rowItem] in [row], or false when the card has none. Every change shows on the
 * row at once: a title taken off Continue Watching (or marked watched there) leaves the row, a
 * watched mark elsewhere changes on its card. Jellyfin's UserDataChanged then refreshes the rows
 * as before, for every other screen and device.
 */
fun Fragment.showHomeCardMenu(rowItem: BaseRowItem, row: ListRow?, tentacleContinueWatching: Boolean = false): Boolean {
	val item = rowItem.baseItem ?: return false
	if (!homeCardHasMenu(item.type)) return false
	val adapter = row?.adapter as? ItemRowAdapter
	// Tentacle's own Continue Watching row (a row of the plugin's items) or Jellyfin's
	val inContinueWatching = tentacleContinueWatching || isContinueWatchingQuery(adapter?.queryType)
	val hasPosition = (item.userData?.playbackPositionTicks ?: 0L) > 0L
	val played = item.userData?.played == true

	val context = requireContext()
	val actions = homeCardChoices(inContinueWatching, hasPosition, played).map { choice ->
		when (choice) {
			HomeCardChoice.REMOVE_FROM_CONTINUE_WATCHING -> TvMenuAction(context.getString(R.string.lbl_remove_from_continue_watching)) {
				removeFromContinueWatching(rowItem, item, adapter)
			}
			HomeCardChoice.PLAY_FROM_START -> TvMenuAction(context.getString(R.string.lbl_play_from_start)) {
				playFromStart(item)
			}
			HomeCardChoice.MARK_WATCHED -> TvMenuAction(context.getString(R.string.lbl_mark_watched)) {
				setWatched(rowItem, item, adapter, played = true, leavesRow = inContinueWatching)
			}
			HomeCardChoice.MARK_UNWATCHED -> TvMenuAction(context.getString(R.string.lbl_mark_unwatched)) {
				setWatched(rowItem, item, adapter, played = false, leavesRow = false)
			}
			HomeCardChoice.DETAILS -> TvMenuAction(context.getString(R.string.lbl_details)) {
				val navigationRepository by inject<NavigationRepository>()
				navigationRepository.navigate(Destinations.itemDetails(item.id, item.serverId?.let(::parseServerId)))
			}
		}
	}
	TvMenus.show(requireActivity(), item.name, actions)
	return true
}

private fun parseServerId(serverId: String) = runCatching { java.util.UUID.fromString(serverId) }.getOrNull()

private fun Fragment.removeFromContinueWatching(rowItem: BaseRowItem, item: BaseItemDto, adapter: ItemRowAdapter?) {
	val itemMutationRepository by inject<ItemMutationRepository>()
	lifecycleScope.launch {
		runCatching { itemMutationRepository.clearResume(item.id) }
			.onSuccess {
				adapter?.remove(rowItem)
				context?.let { Toast.makeText(it, R.string.msg_removed_from_continue_watching, Toast.LENGTH_SHORT).show() }
			}
			.onFailure { error -> showUpdateFailure(error) }
	}
}

private fun Fragment.setWatched(rowItem: BaseRowItem, item: BaseItemDto, adapter: ItemRowAdapter?, played: Boolean, leavesRow: Boolean) {
	val itemMutationRepository by inject<ItemMutationRepository>()
	val api by inject<ApiClient>()
	lifecycleScope.launch {
		runCatching { itemMutationRepository.setPlayed(item.id, played) }
			.onSuccess {
				if (adapter == null) return@onSuccess
				if (leavesRow) adapter.remove(rowItem)
				else adapter.refreshItem(api, viewLifecycleOwner, rowItem)
			}
			.onFailure { error -> showUpdateFailure(error) }
	}
}

private fun Fragment.showUpdateFailure(error: Throwable) {
	error.rethrowIfCancelled()
	Timber.e(error, "Home card menu: the change failed")
	context?.let { Toast.makeText(it, R.string.msg_unable_to_update, Toast.LENGTH_LONG).show() }
}

/** From 0:00, as the user asked: never where 0 wasn't meant (see CLAUDE.md, resume points). */
private fun Fragment.playFromStart(item: BaseItemDto) {
	val playbackHelper by inject<PlaybackHelper>()
	val playbackLauncher by inject<PlaybackLauncher>()
	val context = requireContext()
	playbackHelper.getItemsToPlay(
		context,
		item,
		false,
		false,
		object : Response<List<BaseItemDto>>(lifecycle) {
			override fun onResponse(response: List<BaseItemDto>) {
				if (!isActive || response.isEmpty()) return
				playbackLauncher.launch(context, response, 0, false, 0, false)
			}
		},
	)
}
