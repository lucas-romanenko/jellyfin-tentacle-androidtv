package org.jellyfin.androidtv.ui.activity

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import org.jellyfin.androidtv.data.repository.ActivityDownload
import org.jellyfin.androidtv.data.repository.ActivityResponse
import org.jellyfin.androidtv.data.repository.ActivityUnreleased
import org.jellyfin.androidtv.data.repository.TentacleRepository
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.shared.toolbar.Navbar
import org.jellyfin.androidtv.ui.shared.toolbar.NavbarActiveButton
import org.koin.android.ext.android.inject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w342"

class ActivityFragment : Fragment() {
	private val tentacleRepository by inject<TentacleRepository>()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	) = content {
		JellyfinTheme {
			var activity by remember { mutableStateOf<ActivityResponse?>(null) }
			var isLoading by remember { mutableStateOf(true) }
			val contentFocusRequester = remember { FocusRequester() }

			// Poll every 3 seconds
			LaunchedEffect(Unit) {
				while (true) {
					activity = tentacleRepository.getActivity()
					isLoading = false
					delay(3_000)
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
					val unreleased = activity?.unreleased.orEmpty()

					if (downloads.isEmpty() && unreleased.isEmpty()) {
						Box(
							modifier = Modifier
								.fillMaxSize()
								.focusRequester(contentFocusRequester)
								.focusable(),
							contentAlignment = Alignment.Center,
						) {
							Text(
								text = "No active downloads or upcoming releases",
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
							if (unreleased.isNotEmpty()) {
								item(key = "unreleased") {
									UnreleasedRow(unreleased)
								}
							}
						}
					}
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
			items(downloads, key = { "${it.tmdbId}_${it.episode}" }) { download ->
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
				AsyncImage(
					model = "$TMDB_IMAGE_BASE${download.posterPath}",
					contentDescription = download.title,
					contentScale = ContentScale.Crop,
					modifier = Modifier.fillMaxSize(),
				)
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
			items(unreleased, key = { it.tmdbId }) { item ->
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
				AsyncImage(
					model = "$TMDB_IMAGE_BASE${item.posterPath}",
					contentDescription = item.title,
					contentScale = ContentScale.Crop,
					modifier = Modifier.fillMaxSize(),
				)
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
