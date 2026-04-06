package org.moonfin.playback.emby.mediastream

import org.emby.client.api.MediaInfoServiceApi
import org.emby.client.model.DlnaProfileType
import org.emby.client.model.LiveStreamRequest
import org.emby.client.model.MediaSourceInfo
import org.emby.client.model.PlaybackInfoRequest
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.MediaStreamResolver
import org.jellyfin.playback.core.mediastream.PlayableMediaStream
import org.jellyfin.playback.core.queue.QueueEntry
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.playback.jellyfin.queue.mediaSourceId
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaType
import org.moonfin.playback.emby.profile.toEmbyDeviceProfile
import org.moonfin.server.emby.EmbyApiClient
import timber.log.Timber
import java.net.URLEncoder
import java.util.UUID
import org.jellyfin.sdk.model.api.DeviceProfile as JellyfinDeviceProfile

class EmbyMediaStreamResolver(
	private val api: EmbyApiClient,
	private val deviceProfileBuilder: () -> JellyfinDeviceProfile,
) : MediaStreamResolver {
	companion object {
		private val supportedMediaTypes = arrayOf(MediaType.VIDEO, MediaType.AUDIO)
	}

	override suspend fun getStream(queueEntry: QueueEntry): PlayableMediaStream? {
		if (!api.isConfigured) return null

		val baseItem = queueEntry.baseItem
		if (baseItem == null || !supportedMediaTypes.contains(baseItem.mediaType)) return null

		val mediaInfo = getPlaybackInfo(baseItem, queueEntry.mediaSourceId)

		return when {
			mediaInfo.mediaSource.supportsDirectPlay == true && baseItem.mediaType == MediaType.VIDEO ->
				mediaInfo.toStream(
					queueEntry = queueEntry,
					conversionMethod = MediaConversionMethod.None,
					url = buildStaticStreamUrl("Videos", baseItem.id, mediaInfo.mediaSource),
				)

			mediaInfo.mediaSource.supportsDirectPlay == true && baseItem.mediaType == MediaType.AUDIO ->
				mediaInfo.toStream(
					queueEntry = queueEntry,
					conversionMethod = MediaConversionMethod.None,
					url = buildStaticStreamUrl("Audio", baseItem.id, mediaInfo.mediaSource),
				)

			mediaInfo.mediaSource.supportsDirectStream == true -> {
				val url = mediaInfo.mediaSource.directStreamUrl
					?.let { buildAbsoluteUrl(it, mediaInfo.mediaSource.addApiKeyToDirectStreamUrl != false) }
					?: mediaInfo.mediaSource.transcodingUrl?.let { buildAbsoluteUrl(it) }
					?: return null
				mediaInfo.toStream(
					queueEntry = queueEntry,
					conversionMethod = MediaConversionMethod.Remux,
					url = url,
				)
			}

			mediaInfo.mediaSource.supportsTranscoding == true && baseItem.mediaType == MediaType.AUDIO ->
				mediaInfo.toStream(
					queueEntry = queueEntry,
					conversionMethod = MediaConversionMethod.Transcode,
					url = buildUniversalAudioUrl(baseItem.id, mediaInfo.playSessionId),
				)

			mediaInfo.mediaSource.supportsTranscoding == true && mediaInfo.mediaSource.transcodingUrl != null ->
				mediaInfo.toStream(
					queueEntry = queueEntry,
					conversionMethod = MediaConversionMethod.Transcode,
					url = buildAbsoluteUrl(requireNotNull(mediaInfo.mediaSource.transcodingUrl)),
				)

			else -> null
		}
	}

	private suspend fun getPlaybackInfo(item: BaseItemDto, mediaSourceId: String?): MediaInfo {
		val embyItemId = item.id.toEmbyId()
		val service = requireNotNull(api.mediaInfoService) { "EmbyApiClient mediaInfoService not configured" }
		val embyProfile = deviceProfileBuilder().toEmbyDeviceProfile()

		val response = service.postItemsByIdPlaybackinfo(
			id = embyItemId,
			playbackInfoRequest = PlaybackInfoRequest(
				userId = api.userId,
				mediaSourceId = mediaSourceId,
				deviceProfile = embyProfile,
				enableDirectPlay = true,
				enableDirectStream = true,
				enableTranscoding = true,
				allowVideoStreamCopy = true,
				allowAudioStreamCopy = true,
				autoOpenLiveStream = false,
			),
		).body()

		if (response.errorCode != null) {
			error("Emby playback info failed for item $embyItemId source $mediaSourceId: ${response.errorCode}")
		}

		var mediaSource = response.mediaSources
			.orEmpty()
			.firstOrNull { mediaSourceId == null || it.id == mediaSourceId }
			?: error("No valid media source for item $embyItemId source $mediaSourceId")

		if (mediaSource.requiresOpening == true) {
			Timber.i("Opening live stream for item %s", embyItemId)
			mediaSource = openLiveStream(
				service = service,
				openToken = mediaSource.openToken,
				playSessionId = response.playSessionId,
				itemId = item.id,
			) ?: error("Failed to open live stream for item $embyItemId")
		}

		return MediaInfo(
			playSessionId = response.playSessionId.orEmpty(),
			mediaSource = mediaSource,
		)
	}

	private suspend fun openLiveStream(
		service: MediaInfoServiceApi,
		openToken: String?,
		playSessionId: String?,
		itemId: UUID,
	): MediaSourceInfo? {
		val embyProfile = deviceProfileBuilder().toEmbyDeviceProfile()
		val response = service.postLivestreamsOpen(
			liveStreamRequest = LiveStreamRequest(
				openToken = openToken,
				playSessionId = playSessionId,
				itemId = itemId.toEmbyId().toLongOrNull(),
				userId = api.userId,
				deviceProfile = embyProfile,
				enableDirectPlay = true,
				enableDirectStream = true,
				enableTranscoding = true,
				allowVideoStreamCopy = true,
				allowAudioStreamCopy = true,
			),
		).body()
		return response.mediaSource
	}

	private fun buildStaticStreamUrl(type: String, itemId: UUID, source: MediaSourceInfo): String = buildString {
		append(api.baseUrl.trimEnd('/'))
		append("/$type/")
		append(itemId.toEmbyId())
		append("/stream")
		source.container?.let { append(".$it") }
		append("?Static=true")
		source.id?.let { append("&MediaSourceId=$it") }
		source.liveStreamId?.let { append("&LiveStreamId=$it") }
		append("&api_key=${api.accessToken}")
	}

	private fun buildUniversalAudioUrl(itemId: UUID, playSessionId: String): String {
		val embyProfile = deviceProfileBuilder().toEmbyDeviceProfile()

		val audioDirectPlayContainers = embyProfile.directPlayProfiles
			.orEmpty()
			.filter { it.type == DlnaProfileType.AUDIO }
			.mapNotNull { it.container }
			.flatMap { it.split(",") }
			.distinct()

		val audioTranscodingProfile = embyProfile.transcodingProfiles
			.orEmpty()
			.firstOrNull { it.type == DlnaProfileType.AUDIO }

		return buildString {
			append(api.baseUrl.trimEnd('/'))
			append("/Audio/")
			append(itemId.toEmbyId())
			append("/universal")
			append("?UserId=${api.userId}")
			append("&DeviceId=${URLEncoder.encode(api.deviceId, "UTF-8")}")
			append("&MaxStreamingBitrate=${embyProfile.maxStreamingBitrate ?: 140000000}")
			if (audioDirectPlayContainers.isNotEmpty()) {
				append("&Container=${audioDirectPlayContainers.joinToString(",")}")
			}
			audioTranscodingProfile?.let { tp ->
				tp.protocol?.let { append("&TranscodingProtocol=$it") }
				tp.container?.let { append("&TranscodingContainer=$it") }
				tp.audioCodec?.let { append("&AudioCodec=$it") }
			}
			append("&PlaySessionId=$playSessionId")
			append("&EnableRedirection=true")
			append("&EnableRemoteMedia=false")
			append("&api_key=${api.accessToken}")
		}
	}

	private fun buildAbsoluteUrl(path: String, addApiKey: Boolean = true): String = buildString {
		append(api.baseUrl.trimEnd('/'))
		if (!path.startsWith("/")) append("/")
		append(path)
		if (addApiKey && !path.contains("api_key", ignoreCase = true)) {
			append(if ("?" in path) "&" else "?")
			append("api_key=${api.accessToken}")
		}
	}

	private fun MediaInfo.toStream(
		queueEntry: QueueEntry,
		conversionMethod: MediaConversionMethod,
		url: String,
	) = PlayableMediaStream(
		identifier = playSessionId,
		conversionMethod = conversionMethod,
		container = getMediaStreamContainer(),
		tracks = getTracks(),
		queueEntry = queueEntry,
		url = url,
	)
}

fun UUID.toEmbyId(): String {
	val segment = toString().substringAfterLast("-")
	return segment.trimStart('0').ifEmpty { "0" }
}
