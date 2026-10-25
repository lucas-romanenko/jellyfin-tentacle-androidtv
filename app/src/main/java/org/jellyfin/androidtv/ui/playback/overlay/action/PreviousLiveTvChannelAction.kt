package org.jellyfin.androidtv.ui.playback.overlay.action

import android.content.Context
import android.view.View
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.livetv.TvManager
import org.jellyfin.androidtv.ui.playback.PlaybackController
import org.jellyfin.androidtv.ui.playback.overlay.CustomPlaybackTransportControlGlue
import org.jellyfin.androidtv.ui.playback.overlay.LeanbackOverlayFragment
import org.jellyfin.androidtv.ui.playback.overlay.VideoPlayerAdapter as VideoPlayerAdapter

class PreviousLiveTvChannelAction(
	context: Context,
	customPlaybackTransportControlGlue: CustomPlaybackTransportControlGlue,
) : CustomAction(context, customPlaybackTransportControlGlue) {
	init {
		initializeWithIcon(R.drawable.ic_previous_episode)
	}

	@Override
	override fun handleClickAction(
		playbackController: PlaybackController,
		videoPlayerAdapter: VideoPlayerAdapter,
		context: Context,
		view: View,
	) {
		// No channel watched before this one (first tune since the app started): nothing to go back to.
		// It crashed the app on the Sony (playChannel got null).
		val previous = TvManager.getPrevLiveTvChannel() ?: return
		videoPlayerAdapter.masterOverlayFragment.switchChannel(previous)
	}
}
