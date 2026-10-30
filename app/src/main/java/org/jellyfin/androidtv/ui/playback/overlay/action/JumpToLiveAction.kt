package org.jellyfin.androidtv.ui.playback.overlay.action

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.ui.playback.overlay.CustomPlaybackTransportControlGlue
import org.jellyfin.androidtv.ui.playback.overlay.VideoPlayerAdapter
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.koin.android.ext.android.inject
import timber.log.Timber

/**
 * Jump to live, on a recording still recording played from its start (#239): the recording's
 * channel, switched to in the player as the guide does. Jellyfin joins the live stream the
 * recording already reads, so there is no new provider connection. The channel comes from the
 * recording's timer (a recording carries no channel id).
 */
class JumpToLiveAction(
	context: Context,
	customPlaybackTransportControlGlue: CustomPlaybackTransportControlGlue,
) : CustomAction(context, customPlaybackTransportControlGlue) {
	init {
		initializeWithIcon(R.drawable.ic_tv)
	}

	override fun handleClickAction(
		playbackController: PlaybackController,
		videoPlayerAdapter: VideoPlayerAdapter,
		context: Context,
		view: View,
	) {
		val item = playbackController.currentlyPlayingItem ?: return
		val overlay = videoPlayerAdapter.masterOverlayFragment
		val api by overlay.inject<ApiClient>()
		overlay.lifecycleScope.launch {
			val channelId = item.channelId ?: item.timerId?.let { timerId ->
				runCatching {
					withContext(Dispatchers.IO) { api.liveTvApi.getTimer(timerId).content.channelId }
				}.onFailure { Timber.w(it, "Could not read the timer of %s", item.id) }.getOrNull()
			}
			if (!overlay.isAdded) return@launch
			if (channelId == null) {
				Toast.makeText(context, R.string.msg_live_channel_unavailable, Toast.LENGTH_LONG).show()
				return@launch
			}
			videoPlayerAdapter.leanbackOverlayFragment.hideOverlay()
			overlay.switchChannel(channelId)
		}
	}
}
