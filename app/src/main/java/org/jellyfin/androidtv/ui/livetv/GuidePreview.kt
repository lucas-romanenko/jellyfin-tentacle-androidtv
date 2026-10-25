package org.jellyfin.androidtv.ui.livetv

import android.animation.ValueAnimator
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.data.repository.TentacleRepository
import org.jellyfin.androidtv.ui.composable.item.CardPreviewPlayer
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.time.LocalDateTime
import java.util.UUID

/** How long focus rests on a programme on now before its channel plays in the box. */
const val GUIDE_PREVIEW_DWELL_MS = 1_500L

/** How often the guide asks whether the household has a connection to spare. */
const val GUIDE_PREVIEW_POLL_MS = 15_000L

/**
 * After OK on the previewed channel, the preview's hold on the live stream stays this long, so the
 * full-screen player joins the stream already open (Jellyfin shares it) instead of opening another.
 */
const val GUIDE_PREVIEW_HANDOFF_MS = 10_000L

/** What the header's box shows for the focused programme while previews may run. */
enum class GuidePreviewKind {
	/** On now: its channel, live and muted, after [GUIDE_PREVIEW_DWELL_MS]. */
	LIVE,

	/** Later (or no guide data): the programme's / channel's picture. */
	PICTURE,
}

fun guidePreviewKind(start: LocalDateTime?, end: LocalDateTime?, now: LocalDateTime, noData: Boolean): GuidePreviewKind =
	if (!noData && start != null && end != null && !start.isAfter(now) && end.isAfter(now)) GuidePreviewKind.LIVE
	else GuidePreviewKind.PICTURE

/**
 * Whether the header has its box: only while previews may run (Lucas, 24 Oct: "if not then the text
 * kind of moves over to the left and gets rid of that box"). Decided when the guide opens and when the
 * household's free connections change, never per focused programme, so the text doesn't jump while
 * scrolling. Not known yet (no answer) counts as no previews: a preview never guesses.
 */
fun guidePreviewBoxShown(previewsAllowed: Boolean?): Boolean = previewsAllowed == true

/**
 * The live preview in the guide header's box (#menus job, Lucas 24 Oct). One per device: it plays on
 * the app's one card-preview player, muted, and stops the moment focus moves on, the guide leaves or
 * the app goes to the back. A preview is a viewer like any other (one of the household's provider
 * connections, shared when someone already watches or records the channel), so it only runs while
 * Tentacle says there is one to spare ([TentacleRepository.getLivePreview], asked every
 * [GUIDE_PREVIEW_POLL_MS]); when there isn't, the box goes and the header text moves to the left.
 *
 * Leaving the stream is the stop report alone, never also LiveStreams/Close (that counts the leave
 * twice and can close the stream under a recording or another viewer, see tentacle-share-streams).
 */
@OptIn(UnstableApi::class)
class GuidePreview(
	private val fragment: LiveTvGuideFragment,
	private val box: View,
	private val surface: SurfaceView,
	private val title: TextView,
) {
	private val api by fragment.inject<ApiClient>()
	private val tentacleRepository by fragment.inject<TentacleRepository>()
	private val httpDataSourceFactory by fragment.inject<HttpDataSource.Factory>()

	private val handler = Handler(Looper.getMainLooper())
	private val owner = Any()
	private var allowed: Boolean? = null
	private var boxShown = true
	private var boxAnimator: ValueAnimator? = null
	private val boxWidthPx = box.layoutParams.width
	private val titleMarginPx = (title.layoutParams as ViewGroup.MarginLayoutParams).marginStart

	// The video's first frame is on the surface: the picture over it is hidden
	private var showingVideo = false
	private var focusedChannel: UUID? = null
	private var openJob: Job? = null
	private var playing: Session? = null

	/** A preview's hold on a channel's live stream. */
	private data class Session(val channelId: UUID, val mediaSourceId: String?, val liveStreamId: String?, val playSessionId: String?)

	private var currentPlayer: androidx.media3.exoplayer.ExoPlayer? = null

	private fun player() = currentPlayer.also { currentPlayer = null }

	private val startPreview = Runnable { focusedChannel?.let(::open) }

	/** Asks for spare connections while the guide is on screen; stops the preview when it isn't. */
	fun start() {
		// The last answer this app heard, so a guide opened again doesn't move its text at once
		allowed = lastAllowed
		showBox(guidePreviewBoxShown(lastAllowed), animate = false)
		val viewOwner = fragment.viewLifecycleOwner
		viewOwner.lifecycleScope.launch {
			viewOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				try {
					while (isActive) {
						val answer = tentacleRepository.getLivePreview()
						// No answer: keep what is shown rather than flap the header on a slow request
						if (answer != null) setAllowed(answer.previews)
						delay(GUIDE_PREVIEW_POLL_MS)
					}
				} finally {
					stop()
				}
			}
		}
	}

	private fun setAllowed(value: Boolean) {
		lastAllowed = value
		if (allowed == value) return
		allowed = value
		showBox(guidePreviewBoxShown(value), animate = true)
		if (!value) stop()
	}

	/** The box in or out: its width and the header text's margin animate together (once, not per focus). */
	private fun showBox(shown: Boolean, animate: Boolean) {
		if (boxShown == shown && boxAnimator == null) return
		boxShown = shown
		boxAnimator?.cancel()
		val from = box.layoutParams.width
		val to = if (shown) boxWidthPx else 0
		fun apply(width: Int) {
			box.layoutParams = box.layoutParams.apply { this.width = width }
			surface.layoutParams = surface.layoutParams.apply { this.width = width }
			val fraction = if (boxWidthPx > 0) width.toFloat() / boxWidthPx else 0f
			title.layoutParams = (title.layoutParams as ViewGroup.MarginLayoutParams).apply {
				marginStart = (titleMarginPx * fraction).toInt()
			}
			refreshVisibility()
		}
		if (!animate) {
			apply(to)
			return
		}
		boxAnimator = ValueAnimator.ofInt(from, to).apply {
			duration = 250
			addUpdateListener { apply(it.animatedValue as Int) }
			addListener(object : android.animation.AnimatorListenerAdapter() {
				override fun onAnimationEnd(animation: android.animation.Animator) {
					boxAnimator = null
				}
			})
			start()
		}
	}

	/**
	 * The surface sits under the picture (a SurfaceView only gets a surface while visible), so the
	 * video shows by hiding the picture once its first frame is drawn.
	 */
	private fun refreshVisibility() {
		val width = box.layoutParams.width
		surface.visibility = if (width == 0) View.GONE else View.VISIBLE
		box.visibility = if (width == 0 || showingVideo) View.INVISIBLE else View.VISIBLE
	}

	private fun setShowingVideo(value: Boolean) {
		if (showingVideo == value) return
		showingVideo = value
		refreshVisibility()
	}

	/**
	 * Focus rests on [channelId]'s programme ([kind]): its channel plays after the dwell when it is on
	 * now, else the picture shows. Any earlier preview stops at once.
	 */
	fun onFocus(channelId: UUID?, kind: GuidePreviewKind) {
		handler.removeCallbacks(startPreview)
		if (playing?.channelId == channelId && kind == GuidePreviewKind.LIVE) return
		stop()
		focusedChannel = channelId
		if (channelId == null || allowed != true || kind != GuidePreviewKind.LIVE) return
		handler.postDelayed(startPreview, GUIDE_PREVIEW_DWELL_MS)
	}

	private fun open(channelId: UUID) {
		if (allowed != true || !fragment.isResumed) return
		openJob?.cancel()
		openJob = fragment.lifecycleScope.launch {
			val session = try {
				withContext(Dispatchers.IO) { openStream(channelId) }
			} catch (e: Exception) {
				Timber.w(e, "GuidePreview: could not open channel $channelId")
				null
			} ?: return@launch
			// Focus moved on while it opened: let go of it again
			if (focusedChannel != channelId || allowed != true || !fragment.isResumed) {
				release(session, after = 0)
				return@launch
			}
			play(session)
		}
	}

	/** Jellyfin opens (or joins) the channel's live stream, as the player does (PlaybackManager). */
	private suspend fun openStream(channelId: UUID): Session? {
		val info = api.mediaInfoApi.getPostedPlaybackInfo(
			itemId = channelId,
			data = PlaybackInfoDto(
				// Only the live stream id is used (the tuner's own MPEG-TS, as the player plays it):
				// nothing starts transcoding
				enableDirectPlay = true,
				enableDirectStream = true,
				enableTranscoding = true,
				allowVideoStreamCopy = true,
				allowAudioStreamCopy = true,
				autoOpenLiveStream = true,
			),
		).content
		val source = info.mediaSources.firstOrNull() ?: return null
		val session = Session(channelId, source.id, source.liveStreamId, info.playSessionId)
		if (source.liveStreamId.isNullOrEmpty()) {
			release(session, after = 0)
			return null
		}
		// The session shows the preview as playing, so Jellyfin ends the stream with the session
		// even if this app dies before its stop report
		runCatching {
			api.playStateApi.reportPlaybackStart(
				PlaybackStartInfo(
					itemId = channelId,
					mediaSourceId = source.id,
					liveStreamId = source.liveStreamId,
					playSessionId = info.playSessionId,
					playMethod = PlayMethod.DIRECT_PLAY,
					canSeek = false,
					isPaused = false,
					isMuted = true,
					repeatMode = RepeatMode.REPEAT_NONE,
					playbackOrder = PlaybackOrder.DEFAULT,
				),
			)
		}.onFailure { Timber.w(it, "GuidePreview: start report failed") }
		return session
	}

	private fun play(session: Session) {
		val url = api.videosApi.getVideoStreamUrl(
			itemId = session.channelId,
			container = "ts",
			mediaSourceId = session.mediaSourceId,
			static = true,
			liveStreamId = session.liveStreamId,
		)
		playing = session
		val listener = object : Player.Listener {
			override fun onRenderedFirstFrame() {
				if (playing === session) setShowingVideo(true)
			}

			override fun onPlayerError(error: PlaybackException) {
				Timber.w("GuidePreview: ${error.message}")
				stop()
			}
		}
		val player = CardPreviewPlayer.take(fragment.requireContext(), owner, listener) {
			// A card preview took the player (another screen): this one is over
			setShowingVideo(false)
			currentPlayer = null
			playing?.let { release(it, after = 0) }
			playing = null
		}
		player.setVideoSurfaceView(surface)
		currentPlayer = player
		player.setMediaSource(ProgressiveMediaSource.Factory(httpDataSourceFactory).createMediaSource(MediaItem.fromUri(Uri.parse(url))))
		player.playWhenReady = true
		player.prepare()
	}

	/** Stops the preview now (focus moved, the guide left, the app went to the back, no spare connection). */
	fun stop() {
		handler.removeCallbacks(startPreview)
		openJob?.cancel()
		openJob = null
		val session = playing ?: return
		playing = null
		setShowingVideo(false)
		player()?.clearVideoSurfaceView(surface)
		CardPreviewPlayer.giveBack(owner)
		release(session, after = 0)
	}

	/**
	 * OK on [channelId] while its preview plays: the picture stops now, but the hold on the stream
	 * stays [GUIDE_PREVIEW_HANDOFF_MS] so the player that opens next joins it. True when it was
	 * the previewed channel.
	 */
	fun handOff(channelId: UUID?): Boolean {
		handler.removeCallbacks(startPreview)
		val session = playing
		if (session == null || session.channelId != channelId) {
			stop()
			return false
		}
		playing = null
		setShowingVideo(false)
		player()?.clearVideoSurfaceView(surface)
		CardPreviewPlayer.giveBack(owner)
		release(session, after = GUIDE_PREVIEW_HANDOFF_MS)
		return true
	}

	/** The one leave: the stop report with the live stream id (never LiveStreams/Close as well). */
	private fun release(session: Session, after: Long) {
		releaseScope.launch {
			if (after > 0) delay(after)
			withContext(NonCancellable) {
				runCatching {
					api.playStateApi.reportPlaybackStopped(
						PlaybackStopInfo(
							itemId = session.channelId,
							mediaSourceId = session.mediaSourceId,
							liveStreamId = session.liveStreamId,
							playSessionId = session.playSessionId,
							failed = false,
						),
					)
				}.onFailure { Timber.w(it, "GuidePreview: stop report failed") }
			}
		}
	}

	private companion object {
		// Outlives the guide: a hand-off's delayed leave, and a leave sent as the guide closes
		val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

		var lastAllowed: Boolean? = null
	}
}
