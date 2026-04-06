package org.moonfin.playback.emby.playsession

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.emby.client.model.PlayMethod
import org.emby.client.model.PlaybackProgressInfo
import org.emby.client.model.PlaybackStartInfo
import org.emby.client.model.PlaybackStopInfo
import org.emby.client.model.QueueItem
import org.emby.client.model.RepeatMode as EmbyRepeatMode
import org.jellyfin.playback.core.mediastream.MediaConversionMethod
import org.jellyfin.playback.core.mediastream.mediaStream
import org.jellyfin.playback.core.model.PlayState
import org.jellyfin.playback.core.model.PlaybackOrder
import org.jellyfin.playback.core.plugin.PlayerService
import org.jellyfin.playback.core.queue.queue
import org.jellyfin.playback.jellyfin.queue.baseItem
import org.jellyfin.sdk.model.extensions.inWholeTicks
import org.moonfin.playback.emby.mediastream.toEmbyId
import org.moonfin.server.emby.EmbyApiClient
import timber.log.Timber
import kotlin.math.roundToInt
import org.jellyfin.playback.core.model.RepeatMode as CoreRepeatMode

class EmbyPlaySessionService(
	private val api: EmbyApiClient,
) : PlayerService() {

	override suspend fun onInitialize() {
		state.playState.onEach { playState ->
			when (playState) {
				PlayState.PLAYING -> sendStreamStart()
				PlayState.STOPPED -> sendStreamStop()
				PlayState.PAUSED -> sendStreamUpdate()
				PlayState.ERROR -> sendStreamStop()
			}
		}.launchIn(coroutineScope)
	}

	private val MediaConversionMethod.embyMethod
		get() = when (this) {
			MediaConversionMethod.None -> PlayMethod.DIRECT_PLAY
			MediaConversionMethod.Remux -> PlayMethod.DIRECT_STREAM
			MediaConversionMethod.Transcode -> PlayMethod.TRANSCODE
		}

	private val CoreRepeatMode.embyMode
		get() = when (this) {
			CoreRepeatMode.NONE -> EmbyRepeatMode.REPEAT_NONE
			CoreRepeatMode.REPEAT_ENTRY_ONCE -> EmbyRepeatMode.REPEAT_ONE
			CoreRepeatMode.REPEAT_ENTRY_INFINITE -> EmbyRepeatMode.REPEAT_ALL
		}

	suspend fun sendUpdateIfActive() {
		coroutineScope.launch { sendStreamUpdate() }
	}

	private suspend fun getQueue(): List<QueueItem> {
		return manager.queue
			.peekNext(15)
			.mapNotNull { it.baseItem }
			.map { QueueItem(id = it.id.toEmbyId().toLongOrNull()) }
	}

	private suspend fun sendStreamStart() {
		if (!api.isConfigured) return
		val entry = manager.queue.entry.value ?: return
		val stream = entry.mediaStream ?: return
		val item = entry.baseItem ?: return

		runCatching {
			api.playstateService?.postSessionsPlaying(
				PlaybackStartInfo(
					itemId = item.id.toEmbyId(),
					mediaSourceId = stream.identifier,
					playSessionId = stream.identifier,
					canSeek = true,
					isMuted = state.volume.muted,
					volumeLevel = (state.volume.volume * 100).roundToInt(),
					isPaused = state.playState.value != PlayState.PLAYING,
					aspectRatio = state.videoSize.value.aspectRatio.toString(),
					positionTicks = withContext(Dispatchers.Main) { state.positionInfo.active.inWholeTicks },
					playMethod = stream.conversionMethod.embyMethod,
					repeatMode = state.repeatMode.value.embyMode,
					shuffle = state.playbackOrder.value != PlaybackOrder.DEFAULT,
					nowPlayingQueue = getQueue(),
				)
			)
		}.onFailure { error -> Timber.w(error, "Failed to send Emby playback start") }
	}

	private suspend fun sendStreamUpdate() {
		if (!api.isConfigured) return
		val entry = manager.queue.entry.value ?: return
		val stream = entry.mediaStream ?: return
		val item = entry.baseItem ?: return

		runCatching {
			api.playstateService?.postSessionsPlayingProgress(
				PlaybackProgressInfo(
					itemId = item.id.toEmbyId(),
					mediaSourceId = stream.identifier,
					playSessionId = stream.identifier,
					canSeek = true,
					isMuted = state.volume.muted,
					volumeLevel = (state.volume.volume * 100).roundToInt(),
					isPaused = state.playState.value != PlayState.PLAYING,
					aspectRatio = state.videoSize.value.aspectRatio.toString(),
					positionTicks = withContext(Dispatchers.Main) { state.positionInfo.active.inWholeTicks },
					playMethod = stream.conversionMethod.embyMethod,
					repeatMode = state.repeatMode.value.embyMode,
					shuffle = state.playbackOrder.value != PlaybackOrder.DEFAULT,
					nowPlayingQueue = getQueue(),
				)
			)
		}.onFailure { error -> Timber.w(error, "Failed to send Emby playback progress") }
	}

	private suspend fun sendStreamStop() {
		if (!api.isConfigured) return
		val entry = manager.queue.entry.value ?: return
		val stream = entry.mediaStream ?: return
		val item = entry.baseItem ?: return

		runCatching {
			api.playstateService?.postSessionsPlayingStopped(
				PlaybackStopInfo(
					itemId = item.id.toEmbyId(),
					mediaSourceId = stream.identifier,
					playSessionId = stream.identifier,
					positionTicks = withContext(Dispatchers.Main) { state.positionInfo.active.inWholeTicks },
					failed = false,
					nowPlayingQueue = getQueue(),
				)
			)
		}.onFailure { error -> Timber.w(error, "Failed to send Emby playback stop") }
	}
}
