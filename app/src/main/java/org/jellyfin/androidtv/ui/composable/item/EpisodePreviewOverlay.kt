package org.jellyfin.androidtv.ui.composable.item

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.playback.segment.MediaSegmentRepository
import org.jellyfin.androidtv.util.UUIDUtils
import org.jellyfin.androidtv.util.sdk.ApiClientFactory
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.hlsSegmentApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.koin.compose.koinInject
import timber.log.Timber
import java.util.Collections
import java.util.UUID

/** Delay before starting preview playback to debounce quick scrolling. */
private const val PREVIEW_START_DELAY_MS = 500L

private const val MAX_PREVIEW_DURATION_MS = 30_000L

/** How long a fire-and-forget "stop this preview's transcode" may take. */
private const val STOP_ENCODING_TIMEOUT_MS = 5_000L

/**
 * A preview's own play session (androidtv#47). The transcode behind a preview used to run on
 * for 12-16 s after focus left the card (Jellyfin's kill timer), and for a provider `.strm`
 * item that kept a connection to the IPTV provider open: scrolling a row held several at once
 * and cut a running recording. Tagging the preview's stream with a session id of its own lets
 * it be stopped the moment the preview ends, and only it.
 */
internal fun newPreviewSessionId(): String = UUID.randomUUID().toString().replace("-", "")

/** The preview's stream: H.264 8-bit SDR, no subtitles, tagged with its device and session. */
internal fun previewStreamUrl(api: ApiClient, itemId: UUID, playSessionId: String): String =
	api.videosApi.getVideoStreamUrl(
		itemId = itemId,
		static = false,
		videoCodec = "h264",
		audioCodec = "aac",
		maxVideoBitDepth = 8,
		audioBitRate = 128000,
		audioChannels = 2,
		subtitleMethod = SubtitleDeliveryMethod.DROP,
		deviceId = api.deviceInfo.id,
		playSessionId = playSessionId,
	)

/** Stops the server transcodes behind previews that have ended. */
internal object PreviewEncodings {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	// Sessions already stopped (focus loss, then dispose, can both ask): one request each.
	private val stopped: MutableSet<String> = Collections.synchronizedSet(
		Collections.newSetFromMap(object : LinkedHashMap<String, Boolean>() {
			override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 64
		})
	)

	/**
	 * Fire-and-forget `DELETE /Videos/ActiveEncodings?deviceId=…&playSessionId=…`. Never without
	 * the session id: with a device id alone Jellyfin stops every transcode of this device, the
	 * user's real playback included. Failures are ignored: the kill timer still ends it.
	 */
	fun stop(api: ApiClient, playSessionId: String?) {
		stop(api.deviceInfo.id, playSessionId) { deviceId, sessionId ->
			api.hlsSegmentApi.stopEncodingProcess(deviceId, sessionId)
		}
	}

	/** [stop] with the request passed in: the job, or null when nothing is sent. */
	internal fun stop(deviceId: String, playSessionId: String?, request: suspend (String, String) -> Unit): Job? {
		if (playSessionId.isNullOrBlank() || !stopped.add(playSessionId)) return null
		return scope.launch {
			try {
				withTimeoutOrNull(STOP_ENCODING_TIMEOUT_MS) { request(deviceId, playSessionId) }
			} catch (e: Exception) {
				Timber.d(e, "EpisodePreview: could not stop the preview transcode")
			}
		}
	}
}

private data class PreviewStream(val url: String, val playSessionId: String)

/** Seek to 20% of runtime as a fallback when no intro segment or resume position is available. */
private fun runtimeFallbackMs(item: BaseItemDto): Long {
	val runtimeTicks = item.runTimeTicks ?: 0L
	return if (runtimeTicks > 0) (runtimeTicks / 10_000L) / 5 else 0L
}

/**
 * A composable overlay that plays a muted video preview of an episode/movie
 * directly on top of its card when focused.
 *
 * When [focused] becomes true:
 *  1. Waits [PREVIEW_START_DELAY_MS] to avoid triggering on quick scrolls
 *  2. Resolves a direct play stream URL from Jellyfin
 *  3. Fetches intro segments and seeks past them
 *  4. Starts ExoPlayer muted, fading in over the poster image
 *
 * When [focused] becomes false, the player is stopped and released.
 *
 * @param item The BaseItemDto (episode or movie) to preview
 * @param focused Whether the card is currently focused
 * @param modifier Compose modifier
 */
@OptIn(UnstableApi::class)
@Composable
fun EpisodePreviewOverlay(
	item: BaseItemDto,
	focused: Boolean,
	muted: Boolean = true,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val api = koinInject<ApiClient>()
	val apiClientFactory = koinInject<ApiClientFactory>()
	val mediaSegmentRepository = koinInject<MediaSegmentRepository>()
	val httpDataSourceFactory = koinInject<HttpDataSource.Factory>()

	var stream by remember { mutableStateOf<PreviewStream?>(null) }
	var seekPositionMs by remember { mutableStateOf(0L) }
	var isPlaying by remember { mutableStateOf(false) }
	var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }

	val effectiveApi = remember(item.serverId) {
		val serverId = UUIDUtils.parseUUID(item.serverId)
		if (serverId != null) apiClientFactory.getApiClientForServer(serverId) ?: api else api
	}

	val previewAlpha by animateFloatAsState(
		targetValue = if (isPlaying) 1f else 0f,
		animationSpec = tween(durationMillis = 600),
		label = "previewAlpha",
	)

	LaunchedEffect(focused, item.id) {
		if (!focused) {
			stream = null
			isPlaying = false
			exoPlayer?.stop()
			exoPlayer?.release()
			exoPlayer = null
			return@LaunchedEffect
		}

		delay(PREVIEW_START_DELAY_MS)

		try {
			val (primary, introEndMs) = withContext(Dispatchers.IO) {
				// Transcoded stream first: forces H.264 8-bit (SDR), no Dolby Vision/HDR
				val session = newPreviewSessionId()
				val transcoded = PreviewStream(previewStreamUrl(effectiveApi, item.id, session), session)

				val seekMs = try {
					val positionTicks = item.userData?.playbackPositionTicks ?: 0L
					if (positionTicks > 0) {
						positionTicks / 10_000L
					} else {
						val segments = mediaSegmentRepository.getSegmentsForItem(item)
						val introSegment = segments.firstOrNull { it.type == MediaSegmentType.INTRO }
						introSegment?.endTicks?.let { it / 10_000L }
							?: runtimeFallbackMs(item)
					}
				} catch (e: Exception) {
					Timber.w(e, "EpisodePreview: Failed to get intro segments for ${item.name}")
					val positionTicks = item.userData?.playbackPositionTicks ?: 0L
					if (positionTicks > 0) positionTicks / 10_000L else runtimeFallbackMs(item)
				}

				Pair(transcoded, seekMs)
			}

			stream = primary
			seekPositionMs = introEndMs
		} catch (e: Exception) {
			Timber.w(e, "EpisodePreview: Failed to resolve stream URL for ${item.name}")
		}
	}

	if (stream != null && focused) {
		val current = stream!!
		val currentUrl = current.url

		DisposableEffect(current) {
			val renderersFactory = DefaultRenderersFactory(context).apply {
				setEnableDecoderFallback(true)
				setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
			}

			val trackSelector = DefaultTrackSelector(context).apply {
				parameters = parameters.buildUpon()
					.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
					.build()
			}

			val player = ExoPlayer.Builder(context)
				.setRenderersFactory(renderersFactory)
				.setTrackSelector(trackSelector)
				.build()
				.apply {
					volume = if (muted) 0f else 1f
					repeatMode = Player.REPEAT_MODE_OFF
					playWhenReady = true
					videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
				}

			val mediaSource = ProgressiveMediaSource.Factory(httpDataSourceFactory)
				.createMediaSource(MediaItem.fromUri(Uri.parse(currentUrl)))

			var hasSeeked = false
			val handler = Handler(Looper.getMainLooper())
			val stopRunnable = Runnable {
				isPlaying = false
				player.stop()
				PreviewEncodings.stop(effectiveApi, current.playSessionId)
			}

			player.addListener(object : Player.Listener {
				override fun onPlaybackStateChanged(playbackState: Int) {
					when (playbackState) {
						Player.STATE_READY -> {
							if (!hasSeeked && seekPositionMs > 0) {
								hasSeeked = true
								player.seekTo(seekPositionMs)
							}
							isPlaying = true
							handler.removeCallbacks(stopRunnable)
							handler.postDelayed(stopRunnable, MAX_PREVIEW_DURATION_MS)
						}
						Player.STATE_ENDED -> {
							isPlaying = false
							handler.removeCallbacks(stopRunnable)
						}
						else -> { /* no-op */ }
					}
				}

				override fun onPlayerError(error: PlaybackException) {
					Timber.w("EpisodePreview: Error for ${item.name}: ${error.message}")
					isPlaying = false
					handler.removeCallbacks(stopRunnable)
				}
			})

			player.setMediaSource(mediaSource)
			player.prepare()
			exoPlayer = player

			onDispose {
				handler.removeCallbacks(stopRunnable)
				player.stop()
				player.release()
				exoPlayer = null
				isPlaying = false
				// Focus left the card (or the card went away): end the server side too.
				PreviewEncodings.stop(effectiveApi, current.playSessionId)
			}
		}

		val lifecycleOwner = LocalLifecycleOwner.current
		DisposableEffect(lifecycleOwner, exoPlayer) {
			val observer = LifecycleEventObserver { _, event ->
				when (event) {
					Lifecycle.Event.ON_PAUSE,
					Lifecycle.Event.ON_STOP,
					Lifecycle.Event.ON_DESTROY -> {
						exoPlayer?.stop()
						exoPlayer?.release()
						exoPlayer = null
						isPlaying = false
						PreviewEncodings.stop(effectiveApi, current.playSessionId)
					}
					else -> {}
				}
			}
			lifecycleOwner.lifecycle.addObserver(observer)
			if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
				exoPlayer?.stop()
				exoPlayer?.release()
				exoPlayer = null
				isPlaying = false
				PreviewEncodings.stop(effectiveApi, current.playSessionId)
			}
			onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
		}

		Box(
			modifier = modifier
				.fillMaxSize()
				.alpha(previewAlpha)
				.clip(JellyfinTheme.shapes.medium)
				.background(Color.Black)
		) {
			AndroidView(
				factory = { ctx ->
					PlayerView(ctx).apply {
						this.player = exoPlayer
						useController = false
						resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
						setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
						setKeepContentOnPlayerReset(true)
					}
				},
				update = { playerView ->
					playerView.player = exoPlayer
				},
				modifier = Modifier.fillMaxSize()
			)
		}
	}
}

/** Whether the given item type supports preview playback. */
fun isEligibleForPreview(item: BaseItemDto?): Boolean =
	item?.type == BaseItemKind.EPISODE || item?.type == BaseItemKind.MOVIE || item?.type == BaseItemKind.MUSIC_VIDEO || item?.type == BaseItemKind.VIDEO

/**
 * Whether this item's media is a confirmed local file rather than a provider-backed
 * pointer (a `.strm` file) or a remote/HTTP stream — same distinction the item-details
 * "downloaded content, not VOD" delete-permission gate already makes
 * (ItemDetailsFragment.kt: `!(item.path ?: item.mediaSources?.firstOrNull()?.path ?: "")
 * .endsWith(".strm", ...)`), reused here for the server's `local_only` card-previews policy.
 *
 * A preview under `local_only` is only allowed once this returns true. Home rows and other
 * light-payload listings often omit `Path`/`MediaSources` on the BaseItemDto entirely, so
 * when neither is present we deliberately return false (NOT local) rather than assuming the
 * best case: the whole point of `local_only` is to stop a preview from opening a provider
 * connection, so an item we can't positively confirm as local must be treated as if it
 * weren't.
 */
fun BaseItemDto.hasLocalMedia(): Boolean {
	val source = mediaSources?.firstOrNull()
	val path = path ?: source?.path
	if (path.isNullOrEmpty()) return false
	if (path.endsWith(".strm", ignoreCase = true)) return false
	if (path.startsWith("http://", ignoreCase = true) || path.startsWith("https://", ignoreCase = true)) return false
	if (source?.isRemote == true) return false
	if (source?.protocol == MediaProtocol.HTTP) return false
	return true
}
