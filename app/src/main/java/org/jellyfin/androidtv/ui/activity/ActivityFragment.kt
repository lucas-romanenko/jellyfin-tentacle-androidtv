package org.jellyfin.androidtv.ui.activity

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import coil3.request.crossfade
import kotlinx.coroutines.delay
import org.jellyfin.androidtv.ui.base.button.ButtonDefaults
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.data.repository.ArrActionResult
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.jellyfin.androidtv.data.repository.ActivityDownload
import org.jellyfin.androidtv.data.repository.ActivityRecentlyDownloaded
import org.jellyfin.androidtv.data.repository.ActivityResponse
import org.jellyfin.androidtv.data.repository.ActivitySearching
import org.jellyfin.androidtv.data.repository.ActivityUnreleased
import org.jellyfin.androidtv.data.repository.TentacleRepository
import org.jellyfin.androidtv.util.pollDelay
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.shared.toolbar.Navbar
import org.jellyfin.androidtv.ui.shared.toolbar.NavbarActiveButton
import org.koin.android.ext.android.inject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID


private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w342"

private fun buildPosterUrl(path: String?): String? {
	if (path == null) return null
	if (path.startsWith("http")) return path
	return "$TMDB_IMAGE_BASE$path"
}

/**
 * Poster image for activity cards. Crossfades in, and on load failure leaves the
 * dark card background visible (set by the parent Box) instead of a blank rect.
 * Coil sizes the decode to the composable bounds, so the w342 bitmap isn't
 * decoded oversized for the small (150dp) cards.
 */
@Composable
private fun PosterImage(path: String?, contentDescription: String?) {
	val context = LocalContext.current
	val model = remember(path) {
		coil3.request.ImageRequest.Builder(context)
			.data(buildPosterUrl(path))
			.crossfade(true)
			.build()
	}
	AsyncImage(
		model = model,
		contentDescription = contentDescription,
		contentScale = ContentScale.Crop,
		modifier = Modifier.fillMaxSize(),
	)
}

private const val ACTIVITY_POLL_INTERVAL_MS = 3_000L
private const val ACTIVITY_POLL_MAX_INTERVAL_MS = 60_000L

class ActivityFragment : Fragment() {
	private val tentacleRepository by inject<TentacleRepository>()
	private val navigationRepository by inject<NavigationRepository>()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	) = content {
		JellyfinTheme {
			var activity by remember { mutableStateOf<ActivityResponse?>(null) }
			var isLoading by remember { mutableStateOf(true) }
			val contentFocusRequester = remember { FocusRequester() }
			// The Searching card whose actions (search again / remove) are open.
			var actionItem by remember { mutableStateOf<ActivitySearching?>(null) }

			// Poll for activity updates every 3s while the screen is visible.
			// repeatOnLifecycle stops polling when the fragment is not STARTED.
			// While the server is unreachable the interval backs off (to at most a minute)
			// instead of retrying every 3s through the whole outage.
			val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
			LaunchedEffect(lifecycleOwner) {
				lifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
					var failures = 0
					while (true) {
						val response = tentacleRepository.getActivity()
						if (response != null && response.error.isNullOrBlank()) {
							activity = response
							failures = 0
						} else {
							// A failed poll (the plugin says why in `error`) keeps what is on
							// screen; the reason only shows when there is nothing to keep.
							if (response != null && activity == null) activity = response
							failures++
						}
						isLoading = false
						delay(pollDelay(ACTIVITY_POLL_INTERVAL_MS, failures, ACTIVITY_POLL_MAX_INTERVAL_MS))
					}
				}
			}

			// Focus content after first load
			LaunchedEffect(isLoading) {
				if (!isLoading) {
					try {
						contentFocusRequester.requestFocus()
					} catch (_: Exception) {
					}
				}
			}

			Box(modifier = Modifier.fillMaxSize()) {
			Column(modifier = Modifier.fillMaxSize()) {
				Navbar(activeButton = NavbarActiveButton.Activity)

				if (isLoading) {
					Box(
						modifier = Modifier.fillMaxSize(),
						contentAlignment = Alignment.Center,
					) {
						Text(
							text = "Loading activity\u2026",
							fontSize = 16.sp,
							color = Color.White.copy(alpha = 0.5f),
						)
					}
				} else {
					val downloads = activity?.downloads.orEmpty()
					val searching = activity?.searching.orEmpty()
					val recentlyDownloaded = activity?.recentlyDownloaded.orEmpty()
					val unreleased = activity?.unreleased.orEmpty()
					// Set when the server could not ask Tentacle (busy, not set up) —
					// an empty list then means "unknown", not "nothing happening".
					val unavailable = activity?.message?.takeIf { !activity?.error.isNullOrBlank() }

					if (downloads.isEmpty() && searching.isEmpty() && recentlyDownloaded.isEmpty() && unreleased.isEmpty()) {
						Box(
							modifier = Modifier
								.fillMaxSize()
								.focusRequester(contentFocusRequester)
								.focusable(),
							contentAlignment = Alignment.Center,
						) {
							Text(
								text = unavailable ?: "No active downloads, searches or upcoming releases",
								fontSize = 16.sp,
								color = Color.White.copy(alpha = 0.5f),
							)
						}
					} else {
						LazyColumn(
							modifier = Modifier
								.fillMaxSize()
								.focusRequester(contentFocusRequester),
							contentPadding = PaddingValues(vertical = 16.dp),
							verticalArrangement = Arrangement.spacedBy(24.dp),
						) {
							if (downloads.isNotEmpty()) {
								item(key = "downloads") {
									DownloadsRow(downloads)
								}
							}
							if (searching.isNotEmpty()) {
								item(key = "searching") {
									SearchingRow(searching) { actionItem = it }
								}
							}
							if (recentlyDownloaded.isNotEmpty()) {
								item(key = "recently_downloaded") {
									RecentlyDownloadedRow(recentlyDownloaded) { jellyfinId ->
										navigationRepository.navigate(Destinations.itemDetails(jellyfinId))
									}
								}
							}
							if (unreleased.isNotEmpty()) {
								item(key = "unreleased") {
									UnreleasedRow(unreleased)
								}
							}
						}
					}
				}
			}

			actionItem?.let { item ->
				SearchingActionsPanel(
					item = item,
					onSearch = { tentacleRepository.arrSearchAgain(item) },
					onRemove = {
						val r = tentacleRepository.arrRemove(item)
						if (r.ok) tentacleRepository.getActivity()?.let { fresh -> activity = fresh }
						r
					},
					onDismiss = { actionItem = null },
				)
			}
			}
		}
	}
}

@Composable
private fun DownloadsRow(downloads: List<ActivityDownload>) {
	Column(modifier = Modifier.focusGroup()) {
		Text(
			text = "Downloading",
			fontSize = 20.sp,
			fontWeight = FontWeight.Bold,
			color = Color.White,
			modifier = Modifier.padding(start = 48.dp, bottom = 12.dp),
		)

		LazyRow(
			contentPadding = PaddingValues(horizontal = 48.dp),
			horizontalArrangement = Arrangement.spacedBy(16.dp),
		) {
			// Keys must be unique or Compose throws and takes the app down. The
			// index guarantees that: tmdbId is not unique across this list (TMDB
			// uses separate id spaces for movies and series, so the two can
			// collide) and neither is tmdbId+episode.
			itemsIndexed(downloads, key = { index, it -> "$index:${it.tmdbId}:${it.episode}" }) { _, download ->
				DownloadCard(download)
			}
		}
	}
}

@Composable
private fun DownloadCard(download: ActivityDownload) {
	var isFocused by remember { mutableStateOf(false) }

	Column(
		modifier = Modifier
			.width(150.dp)
			.onFocusChanged { isFocused = it.isFocused }
			.focusable(),
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.aspectRatio(2f / 3f)
				.clip(RoundedCornerShape(8.dp))
				.background(Color(0xFF1a1a2e))
				.then(
					if (isFocused) Modifier.border(3.dp, Color.White, RoundedCornerShape(8.dp))
					else Modifier
				)
		) {
			if (download.posterPath != null) {
				PosterImage(path = download.posterPath, contentDescription = download.title)
			} else {
				Box(
					modifier = Modifier.fillMaxSize(),
					contentAlignment = Alignment.Center,
				) {
					Text(
						text = download.title,
						fontSize = 12.sp,
						color = Color.White.copy(alpha = 0.5f),
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.padding(8.dp),
					)
				}
			}

			// Gradient overlay at bottom
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.height(48.dp)
					.align(Alignment.BottomCenter)
					.background(
						Brush.verticalGradient(
							colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)),
						)
					)
			)

			// Progress bar at bottom
			Column(
				modifier = Modifier
					.align(Alignment.BottomCenter)
					.fillMaxWidth()
					.padding(horizontal = 8.dp, vertical = 6.dp),
			) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceBetween,
				) {
					Text(
						text = "${download.progress.toInt()}%",
						fontSize = 10.sp,
						color = Color.White,
						fontWeight = FontWeight.Bold,
					)
					if (download.eta.isNotBlank()) {
						Text(
							text = download.eta,
							fontSize = 10.sp,
							color = Color.White.copy(alpha = 0.7f),
						)
					}
				}

				Spacer(modifier = Modifier.height(2.dp))

				Box(
					modifier = Modifier
						.fillMaxWidth()
						.height(4.dp)
						.clip(RoundedCornerShape(2.dp))
						.background(Color.White.copy(alpha = 0.2f))
				) {
					Box(
						modifier = Modifier
							.fillMaxHeight()
							.fillMaxWidth(fraction = (download.progress / 100.0).toFloat().coerceIn(0f, 1f))
							.clip(RoundedCornerShape(2.dp))
							.background(
								when (download.status) {
									"downloading" -> Color(0xFF4F46E5)
									"importing" -> Color(0xFF4CAF50)
									"queued" -> Color(0xFF9CA3AF)
									else -> Color(0xFFEAB308)
								}
							)
					)
				}
			}

			// Status badge
			Box(
				modifier = Modifier
					.align(Alignment.TopEnd)
					.padding(6.dp)
					.background(
						color = when (download.status) {
							"downloading" -> Color(0xCC4F46E5)
							"importing" -> Color(0xCC4CAF50)
							"queued" -> Color(0xCCFF9800)
							else -> Color(0xCC757575)
						},
						shape = RoundedCornerShape(4.dp),
					)
					.padding(horizontal = 6.dp, vertical = 2.dp),
			) {
				Text(
					text = download.status.replaceFirstChar { it.uppercase() },
					fontSize = 10.sp,
					color = Color.White,
				)
			}

			if (isFocused) {
				Box(
					modifier = Modifier
						.fillMaxSize()
						.background(Color.White.copy(alpha = 0.08f))
				)
			}
		}

		Spacer(modifier = Modifier.height(6.dp))

		Text(
			text = buildString {
				append(download.title)
				if (download.episode.isNotBlank()) append(" \u00b7 ${download.episode}")
			},
			fontSize = 13.sp,
			fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
			color = if (isFocused) Color.White else Color.White.copy(alpha = 0.8f),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)

		val subtitle = buildList {
			if (download.quality.isNotBlank()) add(download.quality)
			if (download.sizeRemaining.isNotBlank()) add(download.sizeRemaining)
		}.joinToString(" \u2022 ")

		if (subtitle.isNotBlank()) {
			Text(
				text = subtitle,
				fontSize = 11.sp,
				color = Color.White.copy(alpha = 0.5f),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

@Composable
private fun RecentlyDownloadedRow(
	items: List<ActivityRecentlyDownloaded>,
	onNavigate: (UUID) -> Unit,
) {
	Column(modifier = Modifier.focusGroup()) {
		Text(
			text = "Recently Downloaded",
			fontSize = 20.sp,
			fontWeight = FontWeight.Bold,
			color = Color.White,
			modifier = Modifier.padding(start = 48.dp, bottom = 12.dp),
		)

		LazyRow(
			contentPadding = PaddingValues(horizontal = 48.dp),
			horizontalArrangement = Arrangement.spacedBy(16.dp),
		) {
			itemsIndexed(items, key = { index, it -> "$index:${it.tmdbId}" }) { _, item ->
				RecentlyDownloadedCard(item, onNavigate)
			}
		}
	}
}

@Composable
private fun RecentlyDownloadedCard(
	item: ActivityRecentlyDownloaded,
	onNavigate: (UUID) -> Unit,
) {
	var isFocused by remember { mutableStateOf(false) }

	val expiryLabel = when {
		item.hoursRemaining <= 0 -> "Expiring"
		item.hoursRemaining == 1 -> "1h left"
		else -> "${item.hoursRemaining}h left"
	}

	val jellyfinUuid = remember(item.jellyfinItemId) {
		item.jellyfinItemId.takeIf { it.isNotBlank() }?.let {
			runCatching { UUID.fromString(it) }.getOrNull()
		}
	}

	Column(
		modifier = Modifier
			.width(150.dp)
			.onFocusChanged { isFocused = it.isFocused }
			.focusable()
			.then(
				if (jellyfinUuid != null) Modifier.clickable { onNavigate(jellyfinUuid) }
				else Modifier
			),
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.aspectRatio(2f / 3f)
				.clip(RoundedCornerShape(8.dp))
				.background(Color(0xFF1a1a2e))
				.then(
					if (isFocused) Modifier.border(3.dp, Color.White, RoundedCornerShape(8.dp))
					else Modifier
				),
		) {
			if (item.posterPath != null) {
				PosterImage(path = item.posterPath, contentDescription = item.title)
			} else {
				Box(
					modifier = Modifier.fillMaxSize(),
					contentAlignment = Alignment.Center,
				) {
					Text(
						text = item.title,
						fontSize = 12.sp,
						color = Color.White.copy(alpha = 0.5f),
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.padding(8.dp),
					)
				}
			}

			// Green "Downloaded" badge (top-left)
			Box(
				modifier = Modifier
					.align(Alignment.TopStart)
					.padding(6.dp)
					.background(Color(0xCC4CAF50), RoundedCornerShape(4.dp))
					.padding(horizontal = 6.dp, vertical = 2.dp),
			) {
				Text(text = "Downloaded", fontSize = 10.sp, color = Color.White)
			}

			// Expiry badge (top-right)
			Box(
				modifier = Modifier
					.align(Alignment.TopEnd)
					.padding(6.dp)
					.background(
						color = if (item.hoursRemaining <= 6) Color(0xCCEF5350) else Color(0xCC756AE8),
						shape = RoundedCornerShape(4.dp),
					)
					.padding(horizontal = 6.dp, vertical = 2.dp),
			) {
				Text(text = expiryLabel, fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
			}

			if (isFocused) {
				Box(
					modifier = Modifier
						.fillMaxSize()
						.background(Color.White.copy(alpha = 0.08f))
				)
			}
		}

		Spacer(modifier = Modifier.height(6.dp))

		Text(
			text = item.title,
			fontSize = 13.sp,
			fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
			color = if (isFocused) Color.White else Color.White.copy(alpha = 0.8f),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)

		// Show episode label for TV shows, year for movies
		val subtitle = item.episode.takeIf { it.isNotBlank() } ?: item.year
		if (subtitle.isNotBlank()) {
			Text(
				text = subtitle,
				fontSize = 11.sp,
				color = Color.White.copy(alpha = 0.5f),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}

}

/** "5m" / "3h" / "2d" since an ISO-8601 instant, or "" when unknown. */
private fun waitedFor(iso: String?): String {
	if (iso.isNullOrBlank()) return ""
	return try {
		val mins = Duration.between(Instant.parse(iso), Instant.now()).toMinutes()
		when {
			mins < 0 -> ""
			mins < 60 -> "${maxOf(mins, 1)}m"
			mins < 48 * 60 -> "${mins / 60}h"
			else -> "${mins / (24 * 60)}d"
		}
	} catch (_: Exception) {
		""
	}
}

@Composable
private fun SearchingRow(searching: List<ActivitySearching>, onOpen: (ActivitySearching) -> Unit) {
	Column(modifier = Modifier.focusGroup()) {
		Text(
			text = "Searching",
			fontSize = 20.sp,
			fontWeight = FontWeight.Bold,
			color = Color.White,
			modifier = Modifier.padding(start = 48.dp, bottom = 12.dp),
		)

		LazyRow(
			contentPadding = PaddingValues(horizontal = 48.dp),
			horizontalArrangement = Arrangement.spacedBy(16.dp),
		) {
			itemsIndexed(searching, key = { index, it -> "$index:${it.mediaType}:${it.tmdbId}:${it.tvdbId}" }) { _, item ->
				SearchingCard(item) { onOpen(item) }
			}
		}
	}
}

/** A requested title the arrs are still looking for a release of. */
@Composable
private fun SearchingCard(item: ActivitySearching, onClick: () -> Unit) {
	var isFocused by remember { mutableStateOf(false) }
	val waited = remember(item.waitingSince) { waitedFor(item.waitingSince) }

	Column(
		modifier = Modifier
			.width(150.dp)
			.onFocusChanged { isFocused = it.isFocused }
			.clickable(onClick = onClick),
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.aspectRatio(2f / 3f)
				.clip(RoundedCornerShape(8.dp))
				.background(Color(0xFF1a1a2e))
				.then(
					if (isFocused) Modifier.border(3.dp, Color.White, RoundedCornerShape(8.dp))
					else Modifier
				)
		) {
			if (item.posterPath != null) {
				PosterImage(path = item.posterPath, contentDescription = item.title)
			} else {
				Box(
					modifier = Modifier.fillMaxSize(),
					contentAlignment = Alignment.Center,
				) {
					Text(
						text = item.title,
						fontSize = 12.sp,
						color = Color.White.copy(alpha = 0.5f),
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.padding(8.dp),
					)
				}
			}

			// Status badge — same orange as a queued download.
			Box(
				modifier = Modifier
					.align(Alignment.BottomStart)
					.padding(6.dp)
					.background(
						color = Color(0xE6FF9800),
						shape = RoundedCornerShape(4.dp),
					)
					.padding(horizontal = 6.dp, vertical = 2.dp),
			) {
				Text(
					text = if (waited.isNotBlank()) "Searching \u00b7 $waited" else "Searching",
					fontSize = 10.sp,
					color = Color.White,
					fontWeight = FontWeight.Bold,
				)
			}

			if (isFocused) {
				Box(
					modifier = Modifier
						.fillMaxSize()
						.background(Color.White.copy(alpha = 0.08f))
				)
			}
		}

		Spacer(modifier = Modifier.height(6.dp))

		Text(
			text = item.title,
			fontSize = 13.sp,
			fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
			color = if (isFocused) Color.White else Color.White.copy(alpha = 0.8f),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)

		val sub = listOf(item.episode, item.year).firstOrNull { it.isNotBlank() }.orEmpty()
		val requester = item.requestedBy?.takeIf { it.isNotBlank() }
		val line = listOfNotNull(sub.takeIf { it.isNotBlank() }, requester).joinToString(" \u00b7 ")
		if (line.isNotBlank()) {
			Text(
				text = line,
				fontSize = 11.sp,
				color = Color.White.copy(alpha = 0.5f),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}
	}
}

@Composable
private fun UnreleasedRow(unreleased: List<ActivityUnreleased>) {
	Column(modifier = Modifier.focusGroup()) {
		Text(
			text = "Upcoming Releases",
			fontSize = 20.sp,
			fontWeight = FontWeight.Bold,
			color = Color.White,
			modifier = Modifier.padding(start = 48.dp, bottom = 12.dp),
		)

		LazyRow(
			contentPadding = PaddingValues(horizontal = 48.dp),
			horizontalArrangement = Arrangement.spacedBy(16.dp),
		) {
			itemsIndexed(unreleased, key = { index, it -> "$index:${it.tmdbId}:${it.title}" }) { _, item ->
				UnreleasedCard(item)
			}
		}
	}
}

@Composable
private fun UnreleasedCard(item: ActivityUnreleased) {
	var isFocused by remember { mutableStateOf(false) }

	val countdown = remember(item.releaseDate) {
		if (item.releaseDate.isNotBlank()) {
			try {
				val release = LocalDate.parse(item.releaseDate)
				val days = ChronoUnit.DAYS.between(LocalDate.now(), release)
				when {
					days <= 0 -> "Releasing soon"
					days == 1L -> "Tomorrow"
					else -> "$days days"
				}
			} catch (_: Exception) {
				""
			}
		} else ""
	}

	Column(
		modifier = Modifier
			.width(150.dp)
			.onFocusChanged { isFocused = it.isFocused }
			.focusable(),
	) {
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.aspectRatio(2f / 3f)
				.clip(RoundedCornerShape(8.dp))
				.background(Color(0xFF1a1a2e))
				.then(
					if (isFocused) Modifier.border(3.dp, Color.White, RoundedCornerShape(8.dp))
					else Modifier
				)
		) {
			if (item.posterPath != null) {
				PosterImage(path = item.posterPath, contentDescription = item.title)
			} else {
				Box(
					modifier = Modifier.fillMaxSize(),
					contentAlignment = Alignment.Center,
				) {
					Text(
						text = item.title,
						fontSize = 12.sp,
						color = Color.White.copy(alpha = 0.5f),
						maxLines = 2,
						overflow = TextOverflow.Ellipsis,
						modifier = Modifier.padding(8.dp),
					)
				}
			}

			// Release date badge
			if (item.releaseDate.isNotBlank()) {
				Box(
					modifier = Modifier
						.align(Alignment.BottomStart)
						.padding(6.dp)
						.background(
							color = Color(0xCC4F46E5),
							shape = RoundedCornerShape(4.dp),
						)
						.padding(horizontal = 6.dp, vertical = 2.dp),
				) {
					Text(
						text = item.releaseDate,
						fontSize = 10.sp,
						color = Color.White,
					)
				}
			}

			// Countdown badge
			if (countdown.isNotBlank()) {
				Box(
					modifier = Modifier
						.align(Alignment.TopEnd)
						.padding(6.dp)
						.background(
							color = Color(0xCC756AE8),
							shape = RoundedCornerShape(4.dp),
						)
						.padding(horizontal = 6.dp, vertical = 2.dp),
				) {
					Text(
						text = countdown,
						fontSize = 10.sp,
						color = Color.White,
						fontWeight = FontWeight.Bold,
					)
				}
			}

			if (isFocused) {
				Box(
					modifier = Modifier
						.fillMaxSize()
						.background(Color.White.copy(alpha = 0.08f))
				)
			}
		}

		Spacer(modifier = Modifier.height(6.dp))

		Text(
			text = item.title,
			fontSize = 13.sp,
			fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
			color = if (isFocused) Color.White else Color.White.copy(alpha = 0.8f),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)

		if (item.year.isNotBlank()) {
			Text(
				text = item.year,
				fontSize = 11.sp,
				color = Color.White.copy(alpha = 0.5f),
			)
		}
	}
}


/**
 * Search again / Remove for a title still in "Searching" — what used to mean
 * opening Radarr/Sonarr, finding it and deleting it there by hand.
 * Remove takes two presses; the first says exactly what will be deleted.
 */
@Composable
private fun SearchingActionsPanel(
	item: ActivitySearching,
	onSearch: suspend () -> ArrActionResult,
	onRemove: suspend () -> ArrActionResult,
	onDismiss: () -> Unit,
) {
	val scope = rememberCoroutineScope()
	val firstButton = remember { FocusRequester() }
	val removeButton = remember { FocusRequester() }
	val arr = if (item.mediaType == "series") "Sonarr" else "Radarr"
	var busy by remember { mutableStateOf(false) }
	var armed by remember { mutableStateOf(false) }
	var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

	LaunchedEffect(Unit) { runCatching { firstButton.requestFocus() } }
	LaunchedEffect(armed) {
		if (armed) {
			// Keep the remote on the armed button so the confirming press lands on it.
			runCatching { removeButton.requestFocus() }
			delay(5_000)
			armed = false
		}
	}

	// A real Dialog: its own window, so D-pad focus cannot wander to the cards
	// behind it and Back dismisses it (the fragment's navigation otherwise
	// takes Back and leaves the screen).
	Dialog(
		onDismissRequest = onDismiss,
		properties = DialogProperties(usePlatformDefaultWidth = false),
	) {
	Box(
		modifier = Modifier
			.fillMaxSize()
			.background(Color.Black.copy(alpha = 0.7f)),
		contentAlignment = Alignment.Center,
	) {
		Column(
			modifier = Modifier
				.width(560.dp)
				.clip(RoundedCornerShape(12.dp))
				.background(Color(0xFF1a1a2e))
				.padding(28.dp)
				.focusGroup(),
		) {
			Text(text = item.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White,
				maxLines = 2, overflow = TextOverflow.Ellipsis)
			Spacer(modifier = Modifier.height(6.dp))
			val sub = listOfNotNull(
				item.episode.takeIf { it.isNotBlank() },
				waitedFor(item.waitingSince).takeIf { it.isNotBlank() }?.let { "searching for $it" },
			).joinToString(" \u00b7 ")
			Text(text = "In $arr \u2014 no release found yet" + if (sub.isNotBlank()) " ($sub)" else "",
				fontSize = 14.sp, color = Color.White.copy(alpha = 0.6f))
			Spacer(modifier = Modifier.height(20.dp))

			Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
				Button(
					onClick = {
						if (busy) return@Button
						busy = true
						scope.launch {
							val r = onSearch()
							status = (r.message ?: r.detail ?: "") to r.ok
							busy = false
						}
					},
					enabled = !busy,
					modifier = Modifier.focusRequester(firstButton),
				) { Text("Search again") }

				Button(
					onClick = {
						if (busy) return@Button
						if (!armed) {
							armed = true
							return@Button
						}
						armed = false
						busy = true
						scope.launch {
							val r = onRemove()
							busy = false
							if (r.ok) onDismiss() else status = (r.detail ?: "Remove failed") to false
						}
					},
					enabled = !busy,
					modifier = Modifier.focusRequester(removeButton),
					colors = ButtonDefaults.colors(
						containerColor = if (armed) Color(0xFFDC2626) else Color(0x33EF4444),
						contentColor = Color.White,
						focusedContainerColor = Color(0xFFEF4444),
						focusedContentColor = Color.White,
					),
				) {
					Text(
						if (armed) {
							if (item.mediaType == "series") "Press again: delete series + folder"
							else "Press again: delete movie + folder"
						} else "Remove from $arr"
					)
				}

				Button(onClick = onDismiss) { Text("Cancel") }
			}

			status?.let { (text, ok) ->
				if (text.isNotBlank()) {
					Spacer(modifier = Modifier.height(14.dp))
					Text(text = text, fontSize = 14.sp,
						color = if (ok) Color(0xFF50BE82) else Color(0xFFF87171))
				}
			}
		}
	}
	}
}
