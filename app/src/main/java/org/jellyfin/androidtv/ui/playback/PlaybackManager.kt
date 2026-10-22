package org.jellyfin.androidtv.ui.playback

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.data.compat.PlaybackException
import org.jellyfin.androidtv.data.compat.StreamInfo
import org.jellyfin.androidtv.data.compat.VideoOptions
import org.jellyfin.androidtv.util.sdk.ApiClientFactory
import org.jellyfin.androidtv.util.apiclient.Response
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.hlsSegmentApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import timber.log.Timber

private fun createStreamInfo(
	api: ApiClient,
	options: VideoOptions,
	response: PlaybackInfoResponse,
): StreamInfo = StreamInfo().apply {
	val source = response.mediaSources.firstOrNull {
		options.mediaSourceId != null && it.id == options.mediaSourceId
	} ?: response.mediaSources.firstOrNull()

	itemId = options.itemId
	mediaSource = source
	runTimeTicks = source?.runTimeTicks
	playSessionId = response.playSessionId

	if (source == null) return@apply

	fun useDirectPlay() {
		playMethod = PlayMethod.DIRECT_PLAY
		container = source.container
		mediaUrl = api.videosApi.getVideoStreamUrl(
			itemId = itemId,
			container = container,
			mediaSourceId = source.id,
			static = true,
			tag = source.eTag,
			liveStreamId = source.liveStreamId,
		)
	}

	fun useTranscodingUrl(method: PlayMethod) {
		val transUrl = source.transcodingUrl
		if (transUrl != null) {
			playMethod = method
			container = source.transcodingContainer
			mediaUrl = api.createUrl(transUrl, ignorePathParameters = true)
		} else {
			Timber.w("PlaybackManager: transcodingUrl is null for %s, falling back to direct play", method)
			useDirectPlay()
		}
	}

	if (options.enableDirectPlay && source.supportsDirectPlay) {
		playMethod = PlayMethod.DIRECT_PLAY
		container = source.container
		mediaUrl = when {
			source.isRemote && source.path != null -> source.path
			else -> api.videosApi.getVideoStreamUrl(
				itemId = itemId,
				container = container,
				mediaSourceId = source.id,
				static = true,
				tag = source.eTag,
				liveStreamId = source.liveStreamId,
			)
		}
	} else if (options.enableDirectPlay && playsLiveStreamFile(source.isInfiniteStream, source.liveStreamId, source.container)) {
		useDirectPlay()
	} else if (options.enableDirectStream && source.supportsDirectStream) {
		useTranscodingUrl(PlayMethod.DIRECT_STREAM)
	} else if (source.supportsTranscoding) {
		useTranscodingUrl(PlayMethod.TRANSCODE)
	}
}

/**
 * Live TV plays the channel's stream the way the server already buffers it from the tuner
 * (`/Videos/{id}/stream.ts?static=true&LiveStreamId=…`) when the TV can take it as it is.
 *
 * Jellyfin never offers direct play for a tuner with a stream limit, and on the Sony it turned
 * direct stream off too, so it answered with its HLS remux: ffmpeg into 3 s segments, 3-4 s
 * before the first picture, and on a channel switch up to 5 s more while Jellyfin waited for
 * that ffmpeg to quit. The tuner's file is the same MPEG-TS with the same codecs and starts at
 * once. A failure (a codec the TV can't play) falls back to the remux: the retry turns direct
 * play off.
 */
fun playsLiveStreamFile(infinite: Boolean?, liveStreamId: String?, container: String?): Boolean =
	infinite == true && !liveStreamId.isNullOrEmpty() && container?.lowercase() in LIVE_STREAM_FILE_CONTAINERS

private val LIVE_STREAM_FILE_CONTAINERS = setOf("ts", "mpegts")

class PlaybackManager(
	private val api: ApiClient,
	private val apiClientFactory: ApiClientFactory? = null
) {
	/**
	 * Get the appropriate API client for the given options.
	 * Uses server-specific API if serverId is present, otherwise uses default API.
	 */
	private fun getApiClient(options: VideoOptions): ApiClient {
		return if (options.serverId != null && apiClientFactory != null) {
			Timber.d("PlaybackManager: Using server-specific API client for server %s", options.serverId)
			apiClientFactory.getApiClientForServer(options.serverId!!) ?: api
		} else {
			api
		}
	}
	
	fun getVideoStreamInfo(
		lifecycleOwner: LifecycleOwner,
		options: VideoOptions,
		startTimeTicks: Long,
		callback: Response<StreamInfo>,
	) = lifecycleOwner.lifecycleScope.launch {
		getVideoStreamInfoInternal(options, startTimeTicks).fold(
			onSuccess = { callback.onResponse(it) },
			onFailure = { callback.onError(Exception(it)) },
		)
	}

	fun changeVideoStream(
		lifecycleOwner: LifecycleOwner,
		stream: StreamInfo,
		options: VideoOptions,
		startTimeTicks: Long,
		callback: Response<StreamInfo>
	) = lifecycleOwner.lifecycleScope.launch {
		val apiClient = getApiClient(options)
		
		if (stream.playSessionId != null && stream.playMethod != PlayMethod.DIRECT_PLAY) {
			withContext(Dispatchers.IO) {
				apiClient.hlsSegmentApi.stopEncodingProcess(apiClient.deviceInfo.id, stream.playSessionId)
			}
		}

		getVideoStreamInfoInternal(options, startTimeTicks).fold(
			onSuccess = { callback.onResponse(it) },
			onFailure = { callback.onError(Exception(it)) },
		)
	}

	private suspend fun getVideoStreamInfoInternal(
		options: VideoOptions,
		startTimeTicks: Long
	) = runCatching {
		val apiClient = getApiClient(options)
		
		val response = withContext(Dispatchers.IO) {
			apiClient.mediaInfoApi.getPostedPlaybackInfo(
				itemId = requireNotNull(options.itemId) { "Item id cannot be null" },
				data = PlaybackInfoDto(
					mediaSourceId = options.mediaSourceId,
					startTimeTicks = startTimeTicks,
					deviceProfile = options.profile,
					enableDirectStream = options.enableDirectStream,
					enableDirectPlay = options.enableDirectPlay,
					maxAudioChannels = options.maxAudioChannels,
					audioStreamIndex = options.audioStreamIndex.takeIf { it != null && it >= 0 },
					subtitleStreamIndex = options.subtitleStreamIndex,
					allowVideoStreamCopy = true,
					allowAudioStreamCopy = true,
					autoOpenLiveStream = true,
				)
			).content
		}

		if (response.errorCode != null) {
			throw PlaybackException().apply {
				errorCode = response.errorCode!!
			}
		}

		createStreamInfo(apiClient, options, response)
	}
}
