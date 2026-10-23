package org.jellyfin.androidtv.ui.notification

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import coil3.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.data.repository.TentacleNotification
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.itemdetail.v2.TrailerPlayerFragment
import org.jellyfin.androidtv.ui.playback.CustomPlaybackOverlayFragment
import org.jellyfin.androidtv.ui.player.photo.PhotoPlayerFragment
import org.jellyfin.androidtv.ui.player.video.VideoPlayerFragment

internal const val TOAST_SHOW_MS = 8_000L
internal const val TOAST_EXIT_MS = 400L

/**
 * The Tentacle notification toasts, app-wide (MainActivity). They used to live on Home, which
 * stays alive under the screen opened from it: its timer ran and dismissed each notification on
 * the server while Activity, a details page or Search covered it, so nobody saw it (Sony check,
 * 23 Oct 2026). Now a toast is shown only while [canShow] (the app in front, no player, no
 * screensaver), and a notification is marked seen ([onSeen]: dismissed on the server) only once
 * it was on screen for [showMs], or closed or opened by the user. Hidden part way through, it
 * comes back in full the next time toasts can show.
 */
class NotificationToastQueue(
	private val scope: CoroutineScope,
	private val pending: StateFlow<List<TentacleNotification>>,
	private val canShow: StateFlow<Boolean>,
	private val onSeen: suspend (TentacleNotification) -> Unit,
	private val showMs: Long = TOAST_SHOW_MS,
	private val exitMs: Long = TOAST_EXIT_MS,
) {
	private val _shown = MutableStateFlow<TentacleNotification?>(null)

	/** The notification on screen now, or null. */
	val shown: StateFlow<TentacleNotification?> = _shown.asStateFlow()

	/** Seen, while the server is told: not shown again meanwhile. */
	private val leaving = MutableStateFlow<Set<Int>>(emptySet())

	/** When the last one left the screen: the next waits for its exit animation. */
	@Volatile
	private var leftAt = 0L

	fun start(): Job = scope.launch {
		combine(pending, leaving, canShow) { list, gone, ok ->
			if (ok) list.firstOrNull { it.id !in gone } else null
		}
			.distinctUntilChanged()
			.collectLatest { next ->
				if (next == null) {
					_shown.value = null
					return@collectLatest
				}
				val gap = leftAt + exitMs - System.currentTimeMillis()
				if (gap > 0) delay(gap)
				_shown.value = next
				delay(showMs)
				seen(next)
			}
	}

	/** The user closed or opened [notification]: it was seen. */
	fun close(notification: TentacleNotification) = seen(notification)

	private fun seen(notification: TentacleNotification) {
		// Only the one on screen, and once: the timer and the user can both get here
		if (!_shown.compareAndSet(notification, null)) return
		leftAt = System.currentTimeMillis()
		leaving.update { it + notification.id }
		// On the queue's scope: the next toast showing doesn't cancel telling the server
		scope.launch {
			try {
				onSeen(notification)
			} finally {
				leaving.update { it - notification.id }
			}
		}
	}
}

/** The players: no toast over a video, a trailer or a photo slideshow (it never had one). */
private val playerScreens: Set<Class<out Fragment>> = setOf(
	CustomPlaybackOverlayFragment::class.java,
	VideoPlayerFragment::class.java,
	TrailerPlayerFragment::class.java,
	PhotoPlayerFragment::class.java,
)

/** Whether a toast can show: the app in front, not on a player, the screensaver not up. */
internal fun toastCanShow(resumed: Boolean, screen: Class<out Fragment>?, screensaver: Boolean): Boolean =
	resumed && !screensaver && screen !in playerScreens

@Composable
fun NotificationToastHost(
	queue: NotificationToastQueue,
	onOpen: (TentacleNotification) -> Unit,
) {
	val shown by queue.shown.collectAsState()
	// Kept through the exit animation, after shown went back to null
	var last by remember { mutableStateOf<TentacleNotification?>(null) }
	shown?.let { last = it }

	Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
		AnimatedVisibility(
			visible = shown != null,
			enter = slideInHorizontally { it },
			exit = slideOutHorizontally { it },
		) {
			last?.let { notification ->
				NotificationToast(
					notification = notification,
					onDismiss = { queue.close(notification) },
					onClick = {
						queue.close(notification)
						onOpen(notification)
					},
				)
			}
		}
	}
}

@Composable
private fun NotificationToast(
	notification: TentacleNotification,
	onDismiss: () -> Unit,
	onClick: () -> Unit,
) {
	Row(
		modifier = Modifier
			.padding(24.dp)
			.widthIn(max = 380.dp)
			.clip(RoundedCornerShape(12.dp))
			.background(
				Brush.horizontalGradient(
					colors = listOf(Color(0xFF1a1a2e), Color(0xFF16213e))
				)
			)
			.clickable(onClick = onClick)
			.padding(12.dp),
		horizontalArrangement = Arrangement.spacedBy(12.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		if (!notification.posterPath.isNullOrEmpty()) {
			AsyncImage(
				model = "https://image.tmdb.org/t/p/w185${notification.posterPath}",
				contentDescription = null,
				modifier = Modifier
					.size(width = 50.dp, height = 75.dp)
					.clip(RoundedCornerShape(6.dp)),
				contentScale = ContentScale.Crop,
			)
		}

		Column(
			modifier = Modifier.weight(1f),
			verticalArrangement = Arrangement.spacedBy(4.dp),
		) {
			Text(
				text = notificationHeading(notification),
				color = Color(0xFF8b5cf6),
				fontSize = 11.sp,
				fontWeight = FontWeight.Bold,
				letterSpacing = 0.5.sp,
			)
			Text(
				text = notification.message,
				color = Color.White,
				fontSize = 14.sp,
				maxLines = 2,
				overflow = TextOverflow.Ellipsis,
			)
		}

		Text(
			text = "✕",
			color = Color.White.copy(alpha = 0.6f),
			fontSize = 16.sp,
			modifier = Modifier
				.clickable(onClick = onDismiss)
				.padding(4.dp),
		)
	}
}

/**
 * The toast's heading: what the message is about, and whose it is when it's someone else's
 * ("Request · for Abbie"). Without a kind (older servers) it's a download.
 */
internal fun notificationHeading(notification: TentacleNotification): String {
	val what = when (notification.kind) {
		"request" -> "Request"
		"problem" -> notification.title.ifBlank { "Problem" }
		else -> "Ready to Watch"
	}
	val forName = notification.forName?.takeIf { it.isNotBlank() } ?: return what
	return "$what · for $forName"
}

internal const val NOTIFICATION_POLL_MS = 15_000L
internal const val NOTIFICATION_POLL_MAX_MS = 120_000L

/** The next wait of the notification poll: normal after an answer, doubling while none comes. */
internal fun nextNotificationPoll(current: Long, answered: Boolean): Long =
	if (answered) NOTIFICATION_POLL_MS else (current * 2).coerceAtMost(NOTIFICATION_POLL_MAX_MS)
