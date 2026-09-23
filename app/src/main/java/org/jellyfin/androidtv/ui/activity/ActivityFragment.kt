package org.jellyfin.androidtv.ui.activity

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import org.jellyfin.androidtv.data.repository.ArrProblem
import org.jellyfin.androidtv.data.repository.ActivityComingUp
import org.jellyfin.androidtv.data.repository.ReleaseCheck
import org.jellyfin.androidtv.data.repository.ReleaseEntry
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
					val comingUp = activity?.comingUp.orEmpty()
					val problems = activity?.problems.orEmpty()
					// Set when the server could not ask Tentacle (busy, not set up) —
					// an empty list then means "unknown", not "nothing happening".
					val unavailable = activity?.message?.takeIf { !activity?.error.isNullOrBlank() }

					if (downloads.isEmpty() && searching.isEmpty() && recentlyDownloaded.isEmpty() && unreleased.isEmpty()
						&& comingUp.isEmpty() && problems.isEmpty()) {
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
							if (problems.isNotEmpty()) {
								item(key = "problems") { ProblemsBanner(problems) }
							}
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
							if (comingUp.isNotEmpty()) {
								item(key = "coming_up") { ComingUpRow(comingUp) }
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
						val r = tentacleRepository.arrRemove(item, deleteDownloaded = item.episodesOnDisk > 0)
						if (r.ok) tentacleRepository.getActivity()?.let { fresh -> activity = fresh }
						r
					},
					onCheck = { fresh -> tentacleRepository.arrCheck(item, fresh) },
					onGrab = { release ->
						val r = tentacleRepository.arrGrab(item, release)
						if (r.ok) {
							android.widget.Toast.makeText(requireContext(), r.message ?: "Sent to your download client",
								android.widget.Toast.LENGTH_LONG).show()
							tentacleRepository.getActivity()?.let { fresh -> activity = fresh }
						}
						r
					},
					onStopMissing = { episodes ->
						val r = tentacleRepository.arrStopMissing(item, episodes)
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

		item.check?.let { check ->
			Text(
				text = check.short,
				fontSize = 11.sp,
				color = if (check.state == "usable" || check.state == "delayed") Color(0xFF4ADE80) else Color(0xFFFBBF24),
				maxLines = 1,
				overflow = TextOverflow.Ellipsis,
			)
		}

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

/** What in Radarr/Sonarr is stopping downloads — indexers, download client, disk. */
@Composable
private fun ProblemsBanner(problems: List<ArrProblem>) {
	val error = problems.any { it.level == "error" }
	Column(
		modifier = Modifier
			.padding(horizontal = 48.dp)
			.fillMaxWidth()
			.clip(RoundedCornerShape(10.dp))
			.background(if (error) Color(0x33EF4444) else Color(0x33F59E0B))
			.border(1.dp, if (error) Color(0x80EF4444) else Color(0x80F59E0B), RoundedCornerShape(10.dp))
			.padding(horizontal = 16.dp, vertical = 12.dp),
	) {
		Text("\u26A0 Searches may not work right now", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
		problems.take(4).forEach {
			Text("${it.app}: ${it.message}", fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f),
				maxLines = 2, overflow = TextOverflow.Ellipsis)
		}
	}
}

/** "Today 9:00 PM" / "Tomorrow 9:00 PM" / "Thu 9:00 PM" in the TV's time zone. */
private fun airDay(iso: String?): String = try {
	val local = java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault())
	val days = ChronoUnit.DAYS.between(LocalDate.now(), local.toLocalDate())
	val time = local.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
	when {
		days <= 0L -> "Today $time"
		days == 1L -> "Tomorrow $time"
		else -> local.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) + " $time"
	}
} catch (_: Exception) {
	""
}

@Composable
private fun ComingUpRow(items: List<ActivityComingUp>) {
	Column(modifier = Modifier.focusGroup()) {
		Text(
			text = "Coming up this week",
			fontSize = 20.sp,
			fontWeight = FontWeight.Bold,
			color = Color.White,
			modifier = Modifier.padding(start = 48.dp, bottom = 12.dp),
		)
		LazyRow(
			contentPadding = PaddingValues(horizontal = 48.dp),
			horizontalArrangement = Arrangement.spacedBy(16.dp),
		) {
			itemsIndexed(items, key = { index, it -> "$index:${it.tmdbId}:${it.episode}" }) { _, item ->
				ComingUpCard(item)
			}
		}
	}
}

@Composable
private fun ComingUpCard(item: ActivityComingUp) {
	var isFocused by remember { mutableStateOf(false) }
	val day = remember(item.airDateUtc) { airDay(item.airDateUtc) }
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
				.then(if (isFocused) Modifier.border(3.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
		) {
			if (item.posterPath != null) PosterImage(path = item.posterPath, contentDescription = item.title)
			if (day.isNotBlank()) {
				Box(
					modifier = Modifier
						.align(Alignment.BottomStart)
						.padding(6.dp)
						.background(color = Color(0xCC4F46E5), shape = RoundedCornerShape(4.dp))
						.padding(horizontal = 6.dp, vertical = 2.dp),
				) {
					Text(text = day, fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
				}
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
		Text(
			text = listOf(item.episode, item.episodeTitle).filter { it.isNotBlank() }.joinToString(" \u00b7 "),
			fontSize = 11.sp,
			color = Color.White.copy(alpha = 0.5f),
			maxLines = 1,
			overflow = TextOverflow.Ellipsis,
		)
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
 * Search again / stop looking / remove for a title still in "Searching" — what
 * used to mean opening Radarr/Sonarr, finding it and changing it there by hand.
 *
 * Shows: "Stop looking" unmonitors only the missing episodes and keeps
 * everything downloaded (optionally just the chosen ones). Deleting the whole
 * show says how many downloaded episodes go with it. Removing takes two
 * presses; the first says exactly what will be deleted.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchingActionsPanel(
	item: ActivitySearching,
	onSearch: suspend () -> ArrActionResult,
	onRemove: suspend () -> ArrActionResult,
	onStopMissing: suspend (List<String>?) -> ArrActionResult,
	onCheck: suspend (Boolean) -> ReleaseCheck,
	onGrab: suspend (ReleaseEntry) -> ArrActionResult,
	onDismiss: () -> Unit,
) {
	val scope = rememberCoroutineScope()
	val firstButton = remember { FocusRequester() }
	val removeButton = remember { FocusRequester() }
	val isShow = item.mediaType == "series"
	val arr = if (isShow) "Sonarr" else "Radarr"
	val onDisk = if (isShow) item.episodesOnDisk else 0
	var busy by remember { mutableStateOf(false) }
	var armed by remember { mutableStateOf(false) }
	var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
	var choosing by remember { mutableStateOf(false) }
	// "Why? / pick a release": null until asked, then loading, then the check.
	var checking by remember { mutableStateOf(false) }
	var check by remember { mutableStateOf<ReleaseCheck?>(null) }
	val firstRelease = remember { FocusRequester() }
	var unticked by remember { mutableStateOf(setOf<String>()) }
	val labels = item.missingLabels
	val chosen = labels.filterNot { it in unticked }

	LaunchedEffect(Unit) { runCatching { firstButton.requestFocus() } }
	LaunchedEffect(armed) {
		if (armed) {
			// Keep the remote on the armed button so the confirming press lands on it.
			runCatching { removeButton.requestFocus() }
			delay(5_000)
			armed = false
		}
	}

	fun loadCheck(fresh: Boolean) {
		if (checking) return
		checking = true
		check = null
		scope.launch {
			check = onCheck(fresh)
			checking = false
		}
	}
	LaunchedEffect(check) {
		if (check?.releases?.isNotEmpty() == true) {
			delay(100)
			runCatching { firstRelease.requestFocus() }
		}
	}

	fun run(action: suspend () -> ArrActionResult, closeOnOk: Boolean) {
		if (busy) return
		busy = true
		scope.launch {
			val r = action()
			busy = false
			if (r.ok && closeOnOk) onDismiss()
			else status = (r.message ?: r.detail ?: if (r.ok) "" else "Failed") to r.ok
		}
	}

	val stopLabel = when {
		choosing && chosen.size == 1 -> "Stop looking for ${chosen[0]}"
		choosing -> "Stop looking for ${chosen.size} chosen"
		item.missingEpisodes == 1 -> "Stop looking for ${labels.firstOrNull() ?: "1 episode"}"
		item.missingEpisodes > 1 -> "Stop looking for ${item.missingEpisodes} missing"
		else -> "Stop looking for missing episodes"
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
				.width(680.dp)
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
			).joinToString(" · ")
			Text(text = "In $arr — no release found yet" + if (sub.isNotBlank()) " ($sub)" else "",
				fontSize = 14.sp, color = Color.White.copy(alpha = 0.6f))
			if (isShow) {
				Spacer(modifier = Modifier.height(6.dp))
				Text(
					text = (if (onDisk > 0) "Stopping keeps the $onDisk downloaded episode${if (onDisk == 1) "" else "s"} and " else "Stopping keeps ") +
						"new episodes as they air. Undo it from Manage Episodes.",
					fontSize = 13.sp, color = Color.White.copy(alpha = 0.5f),
				)
			}
			Spacer(modifier = Modifier.height(20.dp))

			Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
				Button(
					onClick = { run(onSearch, closeOnOk = false) },
					enabled = !busy,
					modifier = Modifier.focusRequester(firstButton),
				) { Text("Search again") }

				Button(
					onClick = { loadCheck(fresh = check != null) },
					enabled = !busy && !checking,
				) { Text(if (check != null) "Check again" else "Why? / Pick") }

				if (isShow) {
					Button(
						onClick = {
							// All ticked = every missing episode (the list shows at most 50).
							val episodes = if (choosing && chosen.size < labels.size) chosen else null
							run({ onStopMissing(episodes) }, closeOnOk = true)
						},
						enabled = !busy && !(choosing && chosen.isEmpty()),
					) { Text(stopLabel) }
					if (labels.size > 1) {
						Button(onClick = { choosing = !choosing }, enabled = !busy) {
							Text(if (choosing) "All missing" else "Choose…")
						}
					}
				}
			}

			if (isShow && choosing) {
				Spacer(modifier = Modifier.height(14.dp))
				FlowRow(
					modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
					horizontalArrangement = Arrangement.spacedBy(8.dp),
					verticalArrangement = Arrangement.spacedBy(8.dp),
				) {
					labels.forEach { label ->
						val on = label !in unticked
						Button(
							onClick = { unticked = if (on) unticked + label else unticked - label },
							enabled = !busy,
							colors = ButtonDefaults.colors(
								containerColor = if (on) Color(0x556D5FE6) else Color(0x14FFFFFF),
								contentColor = if (on) Color.White else Color.White.copy(alpha = 0.5f),
								focusedContainerColor = Color(0xFF8B80FF),
								focusedContentColor = Color.White,
							),
						) { Text((if (on) "✓ " else "") + label) }
					}
				}
			}

			if (checking) {
				Spacer(modifier = Modifier.height(14.dp))
				Text("Asking your indexers\u2026 this can take up to a minute.", fontSize = 14.sp,
					color = Color.White.copy(alpha = 0.6f))
			}
			check?.let { c ->
				Spacer(modifier = Modifier.height(14.dp))
				Text(c.detail ?: c.summary, fontSize = 14.sp,
					color = if (c.detail != null) Color(0xFFF87171) else Color.White)
				if (c.scope.isNotBlank()) {
					Text("Checked ${c.scope}", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
				}
				if (c.releases.isNotEmpty()) {
					Spacer(modifier = Modifier.height(8.dp))
					LazyColumn(
						modifier = Modifier.heightIn(max = 190.dp),
						verticalArrangement = Arrangement.spacedBy(6.dp),
					) {
						itemsIndexed(c.releases, key = { i, r -> "$i:${r.guid}" }) { i, r ->
							ReleaseRow(
								release = r,
								enabled = !busy,
								modifier = if (i == 0) Modifier.focusRequester(firstRelease) else Modifier,
								onClick = { run({ onGrab(r) }, closeOnOk = true) },
							)
						}
					}
				}
			}

			Spacer(modifier = Modifier.height(12.dp))
			Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
				Button(onClick = onDismiss) { Text("Cancel") }
				Button(
					onClick = {
						if (busy) return@Button
						if (!armed) {
							armed = true
							return@Button
						}
						armed = false
						run(onRemove, closeOnOk = true)
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
						when {
							armed && onDisk > 0 -> "Press again: delete the show and all $onDisk episode${if (onDisk == 1) "" else "s"}"
							armed && isShow -> "Press again: delete series + folder"
							armed -> "Press again: delete movie + folder"
							onDisk > 0 -> "Delete whole show ($onDisk on disk)"
							else -> "Remove from $arr"
						}
					)
				}
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

private fun sizeLabel(bytes: Long): String = when {
	bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
	bytes > 0 -> "${bytes / (1L shl 20)} MB"
	else -> ""
}

/** One release from a check: what it is, why it was turned down, press to download. */
@Composable
private fun ReleaseRow(release: ReleaseEntry, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
	var focused by remember { mutableStateOf(false) }
	Row(
		modifier = modifier
			.fillMaxWidth()
			.onFocusChanged { focused = it.isFocused }
			.clip(RoundedCornerShape(8.dp))
			.background(if (focused) Color(0x556D5FE6) else Color(0x14FFFFFF))
			.clickable(enabled = enabled, onClick = onClick)
			.padding(horizontal = 12.dp, vertical = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Column(modifier = Modifier.weight(1f)) {
			Text(release.title, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
			Text(
				listOf(release.quality, sizeLabel(release.sizeBytes),
					if (release.protocol == "torrent" && release.seeders != null) "${release.seeders} seeders" else release.protocol,
					release.languages).filter { it.isNotBlank() }.joinToString(" \u00b7 "),
				fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f), maxLines = 1,
			)
			if (release.rejected) {
				Text(release.reasons.joinToString(", ").ifBlank { "rejected" }, fontSize = 12.sp,
					color = Color(0xFFFBBF24), maxLines = 1, overflow = TextOverflow.Ellipsis)
			}
		}
		Text(if (release.rejected) "Download anyway" else "Download", fontSize = 13.sp,
			fontWeight = FontWeight.Bold, color = if (focused) Color.White else Color.White.copy(alpha = 0.7f))
	}
}
