package org.jellyfin.androidtv.data.eventhandling

import android.content.Context
import android.media.AudioManager
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.repository.ActivityPoller
import org.jellyfin.androidtv.data.repository.HomeChange
import org.jellyfin.androidtv.data.repository.HomeVersionTracker
import org.jellyfin.androidtv.data.repository.TentacleRepository
import org.jellyfin.androidtv.data.repository.homePushFrom
import org.jellyfin.androidtv.data.syncplay.SyncPlayManager
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.livetv.liveTvVersionFrom
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.playback.PlaybackControllerContainer
import org.jellyfin.androidtv.ui.playback.setSubtitleIndex
import org.jellyfin.androidtv.util.PlaybackHelper
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.sockets.SocketApiState
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.api.sockets.subscribeGeneralCommand
import org.jellyfin.sdk.api.sockets.subscribeGeneralCommands
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.LibraryChangedMessage
import org.jellyfin.sdk.model.api.LibraryUpdateInfo
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlayMessage
import org.jellyfin.sdk.model.api.PlaystateCommand
import org.jellyfin.sdk.model.api.PlaystateMessage
import org.jellyfin.sdk.model.api.RefreshProgressMessage
import org.jellyfin.sdk.model.api.SyncPlayCommandMessage
import org.jellyfin.sdk.model.api.SyncPlayGroupUpdateMessage
import org.jellyfin.sdk.model.extensions.get
import org.jellyfin.sdk.model.extensions.getValue
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.tentacle.server.core.model.EmbyConnectionState
import org.tentacle.server.core.model.ServerType
import org.tentacle.server.core.model.ServerWebSocketMessage
import org.tentacle.server.emby.socket.EmbyWebSocketClient
import timber.log.Timber
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class SocketHandler(
	private val context: Context,
	private val api: ApiClient,
	private val dataRefreshService: DataRefreshService,
	private val mediaManager: MediaManager,
	private val playbackControllerContainer: PlaybackControllerContainer,
	private val navigationRepository: NavigationRepository,
	private val audioManager: AudioManager,
	private val itemLauncher: ItemLauncher,
	private val playbackHelper: PlaybackHelper,
	private val syncPlayManager: SyncPlayManager,
	private val lifecycle: Lifecycle,
	private val embyWebSocketClient: EmbyWebSocketClient,
	private val serverRepository: ServerRepository,
	private val sessionRepository: SessionRepository,
	private val homeVersionTracker: HomeVersionTracker,
	private val activityPoller: ActivityPoller,
	private val tentacleRepository: TentacleRepository,
) {
	// The socket was connected once and then lost: its next Connected is a reconnect.
	@Volatile
	private var socketLost = false
	@Volatile
	private var socketConnectedOnce = false

	private val activeServerType: ServerType
		get() = serverRepository.currentServer.value?.serverType ?: ServerType.JELLYFIN

	init {
		lifecycle.coroutineScope.launch(Dispatchers.IO) {
			lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
				var subscriptionJob: Job? = null
				serverRepository.currentServer
					.onEach { server ->
						subscriptionJob?.cancel()
						embyWebSocketClient.disconnect()
						val type = server?.serverType ?: return@onEach
						subscriptionJob = launch {
							when (type) {
								ServerType.JELLYFIN -> subscribeJellyfin(this)
								ServerType.EMBY -> subscribeEmby(this)
							}
						}
					}
					.launchIn(this)
			}
		}
	}

	suspend fun updateSession() {
		if (activeServerType == ServerType.JELLYFIN) updateJellyfinSession()
	}

	private suspend fun updateJellyfinSession() {
		try {
			withContext(Dispatchers.IO) {
				api.sessionApi.postCapabilities(
					playableMediaTypes = listOf(MediaType.VIDEO, MediaType.AUDIO),
					supportsMediaControl = true,
					supportedCommands = buildList {
						add(GeneralCommandType.DISPLAY_CONTENT)
						add(GeneralCommandType.SET_SUBTITLE_STREAM_INDEX)
						add(GeneralCommandType.SET_AUDIO_STREAM_INDEX)

						add(GeneralCommandType.DISPLAY_MESSAGE)
						add(GeneralCommandType.SEND_STRING)

						if (!audioManager.isVolumeFixed) {
							add(GeneralCommandType.VOLUME_UP)
							add(GeneralCommandType.VOLUME_DOWN)
							add(GeneralCommandType.SET_VOLUME)

							add(GeneralCommandType.MUTE)
							add(GeneralCommandType.UNMUTE)
							add(GeneralCommandType.TOGGLE_MUTE)
						}
					},
				)
			}
		} catch (err: ApiClientException) {
			Timber.e(err, "Unable to update capabilities")
		}
	}

	private fun subscribeJellyfin(coroutineScope: CoroutineScope) = api.webSocket.apply {
		// Tentacle's notifications, fetched at once when it says they changed. Conflated: a burst
		// of pushes while one fetch runs makes one more fetch, not one each.
		val notificationFetches = Channel<Unit>(Channel.CONFLATED)
		coroutineScope.launch {
			while (isActive) {
				notificationFetches.receive()
				tentacleRepository.pollNotifications()
			}
		}

		// A push sent while the socket was down is lost (H3): Home and the navbar check the
		// home version again once it is back, and Tentacle's activity and notifications are
		// asked for at once. The SDK reconnects by itself while subscribed.
		state
			.onEach { state ->
				when (state) {
					is SocketApiState.Connected -> {
						if (socketLost) {
							Timber.i("WebSocket reconnected")
							homeVersionTracker.onReconnected()
							activityPoller.pollNow()
							notificationFetches.trySend(Unit)
						}
						socketLost = false
						socketConnectedOnce = true
					}

					else -> if (socketConnectedOnce) socketLost = true
				}
			}
			.launchIn(coroutineScope)

		// Home's live settings depend on this one, so it resubscribes if the flow ever ends: the
		// app stays in front for days, and Home's own listeners (which this replaces) saw it end
		// silently after a Jellyfin restart.
		coroutineScope.launch {
			while (isActive) {
				try {
					subscribe<LibraryChangedMessage>().collect { message -> message.data?.let(::onLibraryChanged) }
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					Timber.w(e, "WS: LibraryChanged subscription error")
				}
				Timber.w("WS: LibraryChanged subscription ended — resubscribing in 5s")
				delay(5.seconds)
			}
		}

		// Tentacle's push: its requests, activity or notifications changed (Jellyfin's own
		// RefreshProgress, for library scans, carries a real item id and is ignored). The polls
		// stay as the fallback for a push that never comes. Resubscribes like LibraryChanged.
		coroutineScope.launch {
			while (isActive) {
				try {
					subscribe<RefreshProgressMessage>().collect { message ->
						val topics = tentaclePushTopics(message.data) ?: return@collect
						Timber.d("Tentacle push: %s", topics)
						if ("activity" in topics || "requests" in topics) activityPoller.pollNow()
						if ("notifications" in topics) notificationFetches.trySend(Unit)
					}
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					Timber.w(e, "WS: RefreshProgress subscription error")
				}
				Timber.w("WS: RefreshProgress subscription ended — resubscribing in 5s")
				delay(5.seconds)
			}
		}

		subscribe<PlayMessage>()
			.onEach { message -> onPlayMessage(message) }
			.launchIn(coroutineScope)

		subscribe<PlaystateMessage>()
			.onEach { message -> onPlayStateMessage(message) }
			.launchIn(coroutineScope)

		subscribeGeneralCommand(GeneralCommandType.SET_SUBTITLE_STREAM_INDEX)
			.onEach { message ->
				val index = message["index"]?.toIntOrNull() ?: return@onEach

				withContext(Dispatchers.Main) {
					playbackControllerContainer.playbackController?.setSubtitleIndex(index)
				}
			}
			.launchIn(coroutineScope)

		subscribeGeneralCommand(GeneralCommandType.SET_AUDIO_STREAM_INDEX)
			.onEach { message ->
				val index = message["index"]?.toIntOrNull() ?: return@onEach

				withContext(Dispatchers.Main) {
					playbackControllerContainer.playbackController?.switchAudioStream(index)
				}
			}
			.launchIn(coroutineScope)

		subscribeGeneralCommand(GeneralCommandType.DISPLAY_CONTENT)
			.onEach { message ->
				val itemId by message
				val itemType by message

				val itemUuid = itemId?.toUUIDOrNull()
				val itemKind = itemType?.let { type ->
					BaseItemKind.entries.find { value ->
						value.serialName.equals(type, true)
					}
				}

				if (itemUuid != null && itemKind != null) onDisplayContent(itemUuid, itemKind)
			}
			.launchIn(coroutineScope)

		subscribeGeneralCommands(setOf(GeneralCommandType.DISPLAY_MESSAGE, GeneralCommandType.SEND_STRING))
			.onEach { message ->
				val header by message
				val text by message
				val string by message

				onDisplayMessage(header, text ?: string)
			}
			.launchIn(coroutineScope)

		subscribe<SyncPlayCommandMessage>()
			.onEach { message -> onSyncPlayCommand(message) }
			.launchIn(coroutineScope)

		subscribe<SyncPlayGroupUpdateMessage>()
			.onEach { message -> onSyncPlayGroupUpdate(message) }
			.launchIn(coroutineScope)
	}

	private suspend fun subscribeEmby(coroutineScope: CoroutineScope) {
		embyWebSocketClient.connect()

		embyWebSocketClient.messages
			.onEach { message -> handleEmbyMessage(message) }
			.launchIn(coroutineScope)

		embyWebSocketClient.connectionState
			.onEach { state -> handleEmbyConnectionState(state) }
			.launchIn(coroutineScope)
	}

	private suspend fun handleEmbyConnectionState(state: EmbyConnectionState) {
		when (state) {
			is EmbyConnectionState.TokenExpired -> {
				Timber.w("Emby token expired, destroying session")
				withContext(Dispatchers.Main) {
					onDisplayMessage(null, "Session expired. Please sign in again.")
				}
				sessionRepository.destroyCurrentSession()
			}
			is EmbyConnectionState.ServerUnreachable -> {
				Timber.w("Emby server unreachable after max reconnect attempts")
				withContext(Dispatchers.Main) {
					onDisplayMessage(null, "Server is unreachable.")
				}
			}
			else -> Unit
		}
	}

	private suspend fun handleEmbyMessage(message: ServerWebSocketMessage) {
		when (message) {
			is ServerWebSocketMessage.LibraryChanged -> {
				Timber.d("Emby library changed: +${message.itemsAdded.size} ~${message.itemsUpdated.size} -${message.itemsRemoved.size}")
				if (message.itemsAdded.isNotEmpty() || message.itemsRemoved.isNotEmpty()) {
					dataRefreshService.lastLibraryChange = Instant.now()
				}
			}

			is ServerWebSocketMessage.UserDataChanged -> {
				Timber.d("Emby user data changed for ${message.itemIds.size} items")
				dataRefreshService.lastLibraryChange = Instant.now()
			}

			is ServerWebSocketMessage.Play -> {
				val uuids = message.itemIds.mapNotNull { it.toUUIDOrNull() }
				if (uuids.isEmpty()) return
				runCatching {
					playbackHelper.retrieveAndPlay(
						uuids,
						false,
						message.startPositionTicks,
						null,
						context,
					)
				}.onFailure { Timber.w(it, "Failed to start Emby remote playback") }
			}

			is ServerWebSocketMessage.Playstate -> withContext(Dispatchers.Main) {
				if (mediaManager.hasAudioQueueItems()) return@withContext

				val controller = playbackControllerContainer.playbackController
				when (message.command) {
					"Stop" -> controller?.endPlayback(true)
					"Pause", "Unpause", "PlayPause" -> controller?.playPause()
					"NextTrack" -> controller?.next()
					"PreviousTrack" -> controller?.prev()
					"Seek" -> controller?.seek(
						(message.seekPositionTicks ?: 0) / 10_000
					)
					"Rewind" -> controller?.rewind()
					"FastForward" -> controller?.fastForward()
				}
			}

			is ServerWebSocketMessage.GeneralCommand -> handleEmbyGeneralCommand(message)

			is ServerWebSocketMessage.ServerRestarting -> {
				Timber.i("Emby server restarting")
				onDisplayMessage(null, "Server is restarting...")
			}

			is ServerWebSocketMessage.ServerShuttingDown -> {
				Timber.i("Emby server shutting down")
				onDisplayMessage(null, "Server is shutting down...")
			}

			is ServerWebSocketMessage.SessionEnded -> {
				Timber.w("Emby session ended remotely: %s", message.sessionId)
				withContext(Dispatchers.Main) {
					onDisplayMessage(null, "Session ended by server.")
				}
				sessionRepository.destroyCurrentSession()
			}

			is ServerWebSocketMessage.ScheduledTaskEnded -> {
				Timber.d("Emby task ended: ${message.taskName} (${message.status})")
			}
		}
	}

	private suspend fun handleEmbyGeneralCommand(command: ServerWebSocketMessage.GeneralCommand) {
		when (command.name) {
			"DisplayContent" -> {
				val itemId = command.arguments["ItemId"]?.toUUIDOrNull() ?: return
				val itemType = command.arguments["ItemType"]?.let { type ->
					BaseItemKind.entries.find { it.serialName.equals(type, true) }
				}
				if (itemType != null) onDisplayContent(itemId, itemType)
			}

			"DisplayMessage", "SendString" -> {
				onDisplayMessage(
					command.arguments["Header"],
					command.arguments["Text"] ?: command.arguments["String"],
				)
			}

			"SetSubtitleStreamIndex" -> {
				val index = command.arguments["Index"]?.toIntOrNull() ?: return
				withContext(Dispatchers.Main) {
					playbackControllerContainer.playbackController?.setSubtitleIndex(index)
				}
			}

			"SetAudioStreamIndex" -> {
				val index = command.arguments["Index"]?.toIntOrNull() ?: return
				withContext(Dispatchers.Main) {
					playbackControllerContainer.playbackController?.switchAudioStream(index)
				}
			}
		}
	}

	private fun onLibraryChanged(info: LibraryUpdateInfo) {
		Timber.d(buildString {
			appendLine("Library changed.")
			appendLine("Added ${info.itemsAdded.size} items")
			appendLine("Removed ${info.itemsRemoved.size} items")
			appendLine("Updated ${info.itemsUpdated.size} items")
		})

		if (info.itemsAdded.any() || info.itemsRemoved.any())
			dataRefreshService.lastLibraryChange = Instant.now()

		// Always on while the app is in front (also in the player and on other screens), so a
		// home save is remembered even when Home isn't there to hear it (H2).
		val push = homePushFrom(info.collectionFolders, sessionRepository.currentSession.value?.userId)
		if (push != null) Timber.i("Tentacle home version ${push.version} (${if (push.everyone) "everyone" else "this user"})")
		homeVersionTracker.onLibraryChanged(
			HomeChange(push, itemsAdded = info.itemsAdded.any(), liveTvVersion = liveTvVersionFrom(info.collectionFolders))
		)
	}

	private fun onPlayMessage(message: PlayMessage) {
		val itemIds = message.data?.itemIds ?: return

		runCatching {
			playbackHelper.retrieveAndPlay(
				itemIds,
				false,
				message.data?.startPositionTicks,
				message.data?.startIndex,
				context
			)
		}.onFailure { Timber.w(it, "Failed to start playback") }
	}

	@Suppress("ComplexMethod")
	private suspend fun onPlayStateMessage(message: PlaystateMessage) = withContext(Dispatchers.Main) {
		Timber.i("Received PlayStateMessage with command ${message.data?.command}")

		when {
			mediaManager.hasAudioQueueItems() -> {
				Timber.i("Ignoring PlayStateMessage: should be handled by PlaySessionSocketService")
				return@withContext
			}

			else -> {
				val playbackController = playbackControllerContainer.playbackController
				when (message.data?.command) {
					PlaystateCommand.STOP -> playbackController?.endPlayback(true)
					PlaystateCommand.PAUSE, PlaystateCommand.UNPAUSE, PlaystateCommand.PLAY_PAUSE -> playbackController?.playPause()
					PlaystateCommand.NEXT_TRACK -> playbackController?.next()
					PlaystateCommand.PREVIOUS_TRACK -> playbackController?.prev()
					PlaystateCommand.SEEK -> playbackController?.seek(
						org.jellyfin.androidtv.data.syncplay.SyncPlayUtils.ticksToMs(message.data?.seekPositionTicks ?: 0)
					)

					PlaystateCommand.REWIND -> playbackController?.rewind()
					PlaystateCommand.FAST_FORWARD -> playbackController?.fastForward()

					null -> Unit
				}
			}
		}
	}

	private suspend fun onDisplayContent(itemId: UUID, itemKind: BaseItemKind) = withContext(Dispatchers.Main) {
		val playbackController = playbackControllerContainer.playbackController

		if (playbackController?.isPlaying == true || playbackController?.isPaused == true) {
			Timber.i("Not launching $itemId: playback in progress")
			return@withContext
		}

		Timber.i("Launching $itemId")

		when (itemKind) {
			BaseItemKind.USER_VIEW,
			BaseItemKind.COLLECTION_FOLDER -> {
				val item by api.userLibraryApi.getItem(itemId = itemId)
				itemLauncher.launchUserView(item)
			}

			else -> navigationRepository.navigate(Destinations.itemDetails(itemId))
		}
	}

	private fun onDisplayMessage(header: String?, text: String?) {
		val toastMessage = buildString {
			if (!header.isNullOrBlank()) append(header, ": ")
			append(text)
		}

		lifecycle.coroutineScope.launch(Dispatchers.Main) {
			Toast.makeText(context, toastMessage, Toast.LENGTH_LONG).show()
		}
	}

	private fun onSyncPlayCommand(message: SyncPlayCommandMessage) {
		val data = message.data ?: return
		syncPlayManager.onPlaybackCommand(data)
	}

	private fun onSyncPlayGroupUpdate(message: SyncPlayGroupUpdateMessage) {
		val data = message.data ?: return
		syncPlayManager.onGroupUpdate(data)
	}
}

/**
 * The topics of a Tentacle push (requests, activity, notifications), or null when the
 * RefreshProgress is not Tentacle's. The plugin sends ItemId "tentacle" and the changed topics
 * comma-separated in "Tentacle".
 */
internal fun tentaclePushTopics(data: Map<String, String?>?): Set<String>? {
	if (data == null || data["ItemId"] != "tentacle") return null
	return data["Tentacle"].orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
}
