package org.jellyfin.androidtv.ui.itemdetail.v2

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.home.mediabar.SponsorBlockApi
import org.jellyfin.androidtv.ui.home.mediabar.YouTubeStreamResolver
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.koin.android.ext.android.inject
import timber.log.Timber

/** Fullscreen fragment that plays a YouTube trailer via ExoPlayer with sound. */
class TrailerPlayerFragment : Fragment() {

	companion object {
		const val ARG_VIDEO_ID = "VideoId"
		const val ARG_START_SECONDS = "StartSeconds"
		const val ARG_SEGMENTS_JSON = "SegmentsJson"
		private const val RETRY_DELAY_MS = 1_500L
	}

	private val navigationRepository: NavigationRepository by inject()
	private var player: ExoPlayer? = null
	private val mainHandler = Handler(Looper.getMainLooper())
	private var skipRunnable: Runnable? = null
	private var loadingView: View? = null

	@OptIn(UnstableApi::class)
	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View {
		val videoId = requireArguments().getString(ARG_VIDEO_ID)!!
		val startSeconds = requireArguments().getDouble(ARG_START_SECONDS, 0.0)
		val segmentsJson = requireArguments().getString(ARG_SEGMENTS_JSON, "[]")

		val segments = try {
			Json.decodeFromString<List<SegmentDto>>(segmentsJson).map {
				SponsorBlockApi.Segment(it.start, it.end, it.category, it.action)
			}
		} catch (e: Exception) {
			Timber.w(e, "TrailerPlayer: Failed to parse segments JSON")
			emptyList()
		}

		val root = object : FrameLayout(requireContext()) {
			override fun dispatchKeyEvent(event: KeyEvent): Boolean {
				if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
					goBack()
					return true
				}
				// There is no on-screen controller, so give the remote the basics: OK / Play-Pause
				// toggles playback and Left / Right skip 10 s (previously every key but Back was
				// ignored, so a trailer could not even be paused).
				val p = player
				if (p != null && event.action == KeyEvent.ACTION_DOWN) {
					when (event.keyCode) {
						KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
						KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { p.playWhenReady = !p.playWhenReady; return true }
						KeyEvent.KEYCODE_MEDIA_PLAY -> { p.playWhenReady = true; return true }
						KeyEvent.KEYCODE_MEDIA_PAUSE -> { p.playWhenReady = false; return true }
						KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { p.seekTo(p.currentPosition + 10_000); return true }
						KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0)); return true }
					}
				}
				return super.dispatchKeyEvent(event)
			}
		}.apply {
			setBackgroundColor(android.graphics.Color.BLACK)
			isFocusable = true
			isFocusableInTouchMode = true
		}

		val playerView = PlayerView(requireContext()).apply {
			layoutParams = FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.MATCH_PARENT,
				FrameLayout.LayoutParams.MATCH_PARENT,
			)
			useController = false
			resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
			setBackgroundColor(android.graphics.Color.BLACK)
			setShutterBackgroundColor(android.graphics.Color.BLACK)
		}

		root.addView(playerView)

		// Shown while the stream is looked up (1-10 s), so a press doesn't look ignored.
		val spinner = ProgressBar(requireContext()).apply {
			layoutParams = FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.WRAP_CONTENT,
				FrameLayout.LayoutParams.WRAP_CONTENT,
				Gravity.CENTER,
			)
		}
		root.addView(spinner)
		loadingView = spinner

		startTrailer(videoId, startSeconds, segments, playerView)

		return root
	}

	/** Resolves the stream (once more after a short pause if the first lookup fails) and plays it. */
	@OptIn(UnstableApi::class)
	private fun startTrailer(
		videoId: String,
		startSeconds: Double,
		segments: List<SponsorBlockApi.Segment>,
		playerView: PlayerView,
	) {
		loadingView?.isVisible = true
		viewLifecycleOwner.lifecycleScope.launch {
			// A lookup that fails once is often throttling or a slow network: ask once more
			// before telling the user (#45).
			val streamInfo = YouTubeStreamResolver.resolveStream(videoId)
				?: run {
					delay(RETRY_DELAY_MS)
					Timber.i("TrailerPlayer: retrying the stream lookup for $videoId")
					YouTubeStreamResolver.resolveStream(videoId)
				}

			if (streamInfo == null) {
				Timber.w("TrailerPlayer: No stream available for $videoId")
				showFailure(videoId, startSeconds, segments, playerView)
				return@launch
			}

			if (!isAdded) return@launch

			val dataSourceFactory = DefaultHttpDataSource.Factory()

			val exoPlayer = ExoPlayer.Builder(requireContext())
				.setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
				.build()

			exoPlayer.volume = 1f
			exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
			exoPlayer.playWhenReady = true

			exoPlayer.addListener(object : Player.Listener {
				override fun onPlaybackStateChanged(playbackState: Int) {
					if (playbackState == Player.STATE_READY) loadingView?.isVisible = false
					if (playbackState == Player.STATE_ENDED) {
						Timber.d("TrailerPlayer: Playback ended for $videoId")
						goBack()
					}
				}

				override fun onPlayerError(error: PlaybackException) {
					Timber.w(error, "TrailerPlayer: Playback error for $videoId (${error.errorCodeName})")
					releasePlayer()
					showFailure(videoId, startSeconds, segments, playerView)
				}
			})

			if (streamInfo.isVideoOnly && streamInfo.audioUrl != null) {
				val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
					.createMediaSource(MediaItem.fromUri(streamInfo.videoUrl))
				val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
					.createMediaSource(MediaItem.fromUri(streamInfo.audioUrl))
				exoPlayer.setMediaSource(MergingMediaSource(videoSource, audioSource))
			} else {
				exoPlayer.setMediaItem(MediaItem.fromUri(streamInfo.videoUrl))
			}

			exoPlayer.prepare()

			if (startSeconds > 0) {
				exoPlayer.seekTo((startSeconds * 1000).toLong())
			}

			player = exoPlayer
			playerView.player = exoPlayer

			if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
				exoPlayer.pause()
			}

			if (segments.isNotEmpty()) {
				val runnable = object : Runnable {
					override fun run() {
						val p = player ?: return
						if (!p.isPlaying) {
							mainHandler.postDelayed(this, 500)
							return
						}
						val currentSec = p.currentPosition / 1000.0
						for (seg in segments) {
							if (currentSec >= seg.startTime && currentSec < seg.endTime - 0.5) {
								Timber.d("TrailerPlayer: Skipping SponsorBlock segment ${seg.category} at ${seg.startTime}s")
								p.seekTo((seg.endTime * 1000).toLong())
								break
							}
						}
						mainHandler.postDelayed(this, 500)
					}
				}
				skipRunnable = runnable
				mainHandler.postDelayed(runnable, 500)
			}
		}
	}

	/**
	 * The trailer can't be played: say so and let the user try again or open it in the
	 * YouTube app. It used to go back without a word, or jump to the YouTube app (#45).
	 */
	private fun showFailure(
		videoId: String,
		startSeconds: Double,
		segments: List<SponsorBlockApi.Segment>,
		playerView: PlayerView,
	) {
		if (!isAdded) return
		loadingView?.isVisible = false
		val context = requireContext()
		val youtube = Intent(Intent.ACTION_VIEW, "https://www.youtube.com/watch?v=$videoId".toUri())
		val canOpen = youtube.resolveActivity(context.packageManager) != null
		AlertDialog.Builder(context)
			.setTitle(R.string.trailer_unavailable_title)
			.setMessage(R.string.trailer_unavailable_message)
			.setPositiveButton(R.string.lbl_try_again) { _, _ ->
				startTrailer(videoId, startSeconds, segments, playerView)
			}
			.apply {
				if (canOpen) setNeutralButton(R.string.lbl_open_in_youtube) { _, _ ->
					try {
						startActivity(youtube)
					} catch (e: ActivityNotFoundException) {
						Timber.w(e, "TrailerPlayer: no app for $videoId")
					}
					goBack()
				}
			}
			.setNegativeButton(R.string.lbl_close) { _, _ -> goBack() }
			.setOnCancelListener { goBack() }
			.show()
	}

	private fun releasePlayer() {
		skipRunnable?.let { mainHandler.removeCallbacks(it) }
		skipRunnable = null
		player?.release()
		player = null
	}

	private fun goBack() {
		if (navigationRepository.canGoBack) {
			navigationRepository.goBack()
		}
	}

	override fun onPause() {
		super.onPause()
		player?.pause()
	}

	override fun onResume() {
		super.onResume()
		player?.play()
	}

	override fun onDestroyView() {
		super.onDestroyView()
		releasePlayer()
		loadingView = null
	}
}

@kotlinx.serialization.Serializable
private data class SegmentDto(
	val start: Double,
	val end: Double,
	val category: String = "",
	val action: String = "skip",
)
