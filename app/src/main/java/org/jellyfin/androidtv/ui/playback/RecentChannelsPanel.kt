package org.jellyfin.androidtv.ui.playback

import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.data.repository.HomeVersionTracker
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.browsing.v2.GuideAccent
import org.jellyfin.androidtv.ui.browsing.v2.GuideBackground
import org.jellyfin.androidtv.ui.browsing.v2.GuideCardFrame
import org.jellyfin.androidtv.ui.browsing.v2.GuideTextMuted
import org.jellyfin.androidtv.ui.browsing.v2.getCardUrl
import org.jellyfin.androidtv.ui.browsing.v2.rememberCardPx
import org.jellyfin.androidtv.ui.composable.requestFocusSettled
import org.jellyfin.androidtv.ui.home.timeLeft
import org.jellyfin.androidtv.ui.livetv.RecentChannelsList
import org.jellyfin.androidtv.ui.livetv.RecentChannelsStore
import org.jellyfin.androidtv.ui.livetv.currentLiveTvUserId
import org.jellyfin.androidtv.ui.livetv.fetchRecentChannels
import org.jellyfin.androidtv.ui.livetv.isCurrentUser
import org.jellyfin.androidtv.ui.livetv.programProgress
import org.jellyfin.androidtv.ui.livetv.recentChannelsChanged
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.UserDataChangedMessage
import org.koin.android.ext.android.inject
import org.tentacle.server.core.model.ServerType
import timber.log.Timber
import java.time.LocalDateTime
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The live player's channel row (the channels button, or Down while a channel plays): the
 * person's recent channels, most recent first, the one playing left out, so OK on the first card
 * goes back to the last channel; their favourites when they have no recents yet. Cards as the
 * Favorites screen's channels: the logo, the number, what's on now and how far it is, a purple
 * outline on focus.
 *
 * It opens at once with the row read last (read when each channel starts, [prefetch]) and is read
 * again behind it. While it is open it follows changes made anywhere (Jellyfin's UserDataChanged:
 * a favourite changed, a channel played on another device; Tentacle's Live TV setup ran): the row
 * changes in place, focus stays.
 */
class RecentChannelsPanel(
	private val fragment: CustomPlaybackOverlayFragment,
	parent: ViewGroup,
	private val onPick: (UUID) -> Unit,
	private val onClose: () -> Unit,
	private val onChannelKey: (up: Boolean) -> Unit,
) {
	private val api by fragment.inject<ApiClient>()
	private val serverRepository by fragment.inject<ServerRepository>()
	private val homeVersionTracker by fragment.inject<HomeVersionTracker>()

	private var list by mutableStateOf<RecentChannelsList?>(RecentChannelsStore.cached())
	private var loaded by mutableStateOf(false)
	private var opened by mutableStateOf(0)
	private var readJob: Job? = null
	private var liveJob: Job? = null
	private var openedAtMs = 0L
	// The key pressed down inside the row: its release acts. The Down that opens the row is pressed
	// in the player, and its release must not close the row again
	private var keyDownInRow = 0
	// Where focus was before the row opened: it goes back there on close, else nothing has focus
	// and the player's keys stop working
	private var focusBefore: View? = null

	private val view = ComposeView(parent.context).apply {
		layoutParams = FrameLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			ViewGroup.LayoutParams.WRAP_CONTENT,
			Gravity.BOTTOM,
		)
		isVisible = false
		setContent { JellyfinTheme { Panel() } }
	}.also(parent::addView)

	val isShowing: Boolean get() = view.isVisible

	fun show() {
		if (isShowing) return
		openedAtMs = android.os.SystemClock.uptimeMillis()
		keyDownInRow = 0
		focusBefore = fragment.activity?.currentFocus
		list = RecentChannelsStore.cached() ?: list
		opened++
		view.isVisible = true
		read()
		follow()
	}

	fun hide() {
		if (!isShowing) return
		liveJob?.cancel()
		liveJob = null
		view.isVisible = false
		val before = focusBefore
		focusBefore = null
		if (before == null || !before.isAttachedToWindow || !before.requestFocus()) fragment.view?.requestFocus()
	}

	/** A channel started: read the row now, so the next open shows it at once. */
	fun prefetch() {
		if (!isShowing) read()
	}

	private fun currentChannel(): UUID? = fragment.playbackController?.currentlyPlayingItem?.id

	private fun read() {
		readJob?.cancel()
		readJob = fragment.lifecycleScope.launch {
			try {
				val next = fetchRecentChannels(api, currentChannel())
				if (recentChannelsChanged(list, next)) list = next
				loaded = true
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				// The row read last stays; the next open reads again
				Timber.w(e, "Unable to read the recent channels")
				loaded = true
			}
		}
	}

	/** While open: changes from anywhere read the row again (a burst is one read). */
	private fun follow() {
		liveJob?.cancel()
		liveJob = fragment.lifecycleScope.launch {
			val settle = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
			launch {
				for (ignored in settle) {
					delay(SETTLE)
					read()
				}
			}
			launch { homeVersionTracker.changes.collect { settle.trySend(Unit) } }
			launch { homeVersionTracker.reconnects.collect { settle.trySend(Unit) } }
			if (serverRepository.currentServer.value?.serverType != ServerType.EMBY) launch {
				val me = currentLiveTvUserId()?.toString()
				while (isActive) {
					try {
						api.webSocket.subscribe<UserDataChangedMessage>().collect { message ->
							val data = message.data ?: return@collect
							// Our favourites and plays (from any device); channel pool changes come as LibraryChanged
							if (isCurrentUser(me, data.userId.toString())) settle.trySend(Unit)
						}
					} catch (e: CancellationException) {
						throw e
					} catch (e: Exception) {
						Timber.w(e, "Recent channels: UserDataChanged subscription error")
					}
					delay(5.seconds)
				}
			}
		}
	}

	/** True on the release of a key that was also pressed in the row. */
	private fun releasedInRow(event: KeyEvent): Boolean {
		if (event.action == KeyEvent.ACTION_DOWN) {
			if (event.repeatCount == 0) keyDownInRow = event.keyCode
			return false
		}
		if (event.action != KeyEvent.ACTION_UP || keyDownInRow != event.keyCode) return false
		keyDownInRow = 0
		return true
	}

	@Composable
	private fun Panel() {
		val shown = list
		val firstFocus = remember { FocusRequester() }
		var firstFocused by remember { mutableStateOf(false) }
		val rowState = rememberLazyListState()
		val firstId = shown?.channels?.firstOrNull()?.id

		// Each open starts on the first card (the channel to go back to)
		LaunchedEffect(opened, firstId != null) {
			if (opened == 0 || firstId == null) return@LaunchedEffect
			rowState.scrollToItem(0)
			firstFocus.requestFocusSettled(isFocused = { firstFocused })
			android.util.Log.i("PlayerTiming", "channel row shown +${android.os.SystemClock.uptimeMillis() - openedAtMs} ms, ${shown.channels.size} channels")
		}

		var now by remember { mutableStateOf(LocalDateTime.now()) }
		LaunchedEffect(Unit) {
			while (true) {
				delay((60_000L - (System.currentTimeMillis() % 60_000L)).milliseconds)
				now = LocalDateTime.now()
			}
		}

		Column(
			modifier = Modifier
				.fillMaxWidth()
				.onPreviewKeyEvent { event ->
					// Here, not in the player's key handling: with nothing further up or down in the
					// row, focus could otherwise leave for the hidden player controls
					val native = event.nativeKeyEvent
					when (native.keyCode) {
						KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
						KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> {
							if (releasedInRow(native)) onClose()
							true
						}
						KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
							if (releasedInRow(native)) onChannelKey(native.keyCode == KeyEvent.KEYCODE_CHANNEL_UP)
							true
						}
						else -> false
					}
				}
				.background(Brush.verticalGradient(listOf(Color.Transparent, GuideBackground.copy(alpha = 0.92f), GuideBackground)))
				.padding(top = 36.dp, bottom = 28.dp),
		) {
			Text(
				text = stringResource(if (shown?.favorites == true) R.string.lbl_recent_channels_favorites else R.string.lbl_recent_channels),
				fontSize = 18.sp,
				fontWeight = FontWeight.Bold,
				color = Color.White,
				modifier = Modifier.padding(start = 48.dp, bottom = 10.dp),
			)
			when {
				shown != null && shown.channels.isNotEmpty() -> LazyRow(
					state = rowState,
					contentPadding = PaddingValues(horizontal = 48.dp),
					horizontalArrangement = Arrangement.spacedBy(14.dp),
				) {
					itemsIndexed(shown.channels, key = { _, channel -> channel.id }) { index, channel ->
						ChannelCard(
							channel = channel,
							now = now,
							modifier = if (index == 0) Modifier.focusRequester(firstFocus).onFocusChanged { firstFocused = it.isFocused } else Modifier,
						)
					}
				}
				else -> Text(
					text = stringResource(if (loaded) R.string.msg_no_recent_channels else R.string.loading),
					fontSize = 14.sp,
					color = GuideTextMuted,
					modifier = Modifier
						.padding(start = 48.dp)
						.height(CARD_HEIGHT.dp + 40.dp),
				)
			}
		}
	}

	@Composable
	private fun ChannelCard(channel: BaseItemDto, now: LocalDateTime, modifier: Modifier) {
		val px = rememberCardPx(CARD_WIDTH, CARD_HEIGHT)
		val logoUrl = remember(channel.id, channel.imageTags, px) {
			channel.itemImages[ImageType.PRIMARY]?.getCardUrl(api, px.width, px.height)
		}
		val program = channel.currentProgram
		val progress = programProgress(program?.startDate, program?.endDate, now)
		val onNow = program != null && progress >= 0f
		GuideCardFrame(
			width = CARD_WIDTH,
			height = CARD_HEIGHT,
			onClick = { onPick(channel.id) },
			onMenu = null,
			onFocused = {},
			title = channel.name.orEmpty(),
			subtitle = if (onNow) listOfNotNull(program.name, program.endDate?.let { timeLeft(now, it) }).joinToString(" · ") else "",
			modifier = modifier,
		) {
			if (logoUrl != null) {
				AsyncImage(
					model = logoUrl,
					contentDescription = channel.name,
					modifier = Modifier
						.fillMaxSize()
						.padding(horizontal = 30.dp, vertical = 20.dp),
					contentScale = ContentScale.Fit,
				)
			} else {
				Text(
					text = channel.name.orEmpty(),
					fontSize = 16.sp,
					fontWeight = FontWeight.Bold,
					color = Color.White,
					maxLines = 2,
					modifier = Modifier.align(Alignment.Center).padding(horizontal = 12.dp),
				)
			}
			channel.number?.takeIf { it.isNotBlank() }?.let { number ->
				Box(
					modifier = Modifier
						.align(Alignment.TopStart)
						.padding(6.dp)
						.clip(RoundedCornerShape(3.dp))
						.background(Color.Black.copy(alpha = 0.55f))
						.padding(horizontal = 5.dp, vertical = 1.dp),
				) {
					Text(text = number, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
				}
			}
			if (onNow) {
				Box(
					modifier = Modifier
						.align(Alignment.BottomStart)
						.fillMaxWidth(progress.coerceIn(0.02f, 1f))
						.height(3.dp)
						.background(GuideAccent),
				)
			}
		}
	}

	private companion object {
		const val CARD_WIDTH = 200
		const val CARD_HEIGHT = 112
		val SETTLE = 300.milliseconds
	}
}
