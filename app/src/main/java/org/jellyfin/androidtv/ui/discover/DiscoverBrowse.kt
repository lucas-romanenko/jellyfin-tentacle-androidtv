package org.jellyfin.androidtv.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jellyfin.androidtv.data.repository.DiscoverGenre
import org.jellyfin.androidtv.data.repository.DiscoverItem
import org.jellyfin.androidtv.data.repository.DiscoverList
import org.jellyfin.androidtv.data.repository.StreamingProvider
import org.jellyfin.androidtv.data.repository.TentacleRepository
import org.jellyfin.androidtv.ui.base.Text

// Discover browse sections — parity with the Jellyfin-web/plugin Discover
// pickers: New on Streaming, Genres (Top Rated / Newly Released), and a
// From My Lists picker with an "All" (mixed) tab plus one tab per list.
// Each holds its own selection state and lazily loads content on change,
// reusing DiscoverCard and the shared detail dialog via onItemClick.

private const val ROW_START = 48
private val PURPLE = Color(0xFF7B1FA2)
private val PILL_IDLE = Color(0xFF2A2A3E)

@Composable
private fun FilterPill(
	text: String,
	selected: Boolean,
	onClick: () -> Unit,
) {
	var focused by remember { mutableStateOf(false) }
	val background = when {
		focused -> Color.White
		selected -> PURPLE
		else -> PILL_IDLE
	}
	val foreground = if (focused) Color.Black else Color.White
	Box(
		modifier = Modifier
			.onFocusChanged { focused = it.isFocused }
			.clickable { onClick() }
			.background(background, RoundedCornerShape(20.dp))
			.padding(horizontal = 16.dp, vertical = 8.dp),
	) {
		Text(
			text = text,
			fontSize = 14.sp,
			color = foreground,
			fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Normal,
		)
	}
}

@Composable
private fun BrowseHeader(title: String) {
	Text(
		text = title,
		fontSize = 20.sp,
		fontWeight = FontWeight.Bold,
		color = Color.White,
		modifier = Modifier.padding(start = ROW_START.dp, bottom = 12.dp),
	)
}

/** A horizontal pill selector, aligned with the content rows. */
@Composable
private fun PillRow(
	content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
	LazyRow(
		contentPadding = PaddingValues(horizontal = ROW_START.dp),
		horizontalArrangement = Arrangement.spacedBy(10.dp),
		modifier = Modifier.padding(bottom = 10.dp),
		content = content,
	)
}

/** The content strip: loading / empty placeholder or a row of poster cards. */
@Composable
private fun ContentRow(
	items: List<DiscoverItem>,
	isLoading: Boolean,
	emptyText: String,
	onItemClick: (DiscoverItem) -> Unit,
) {
	when {
		isLoading -> Placeholder("Loading...")
		items.isEmpty() -> Placeholder(emptyText)
		else -> LazyRow(
			contentPadding = PaddingValues(horizontal = ROW_START.dp),
			horizontalArrangement = Arrangement.spacedBy(16.dp),
		) {
			itemsIndexed(
				items,
				key = { index, it -> "$index:${it.mediaType}:${it.tmdbId}:${it.tvdbId}" },
			) { _, item ->
				DiscoverCard(item = item, onClick = { onItemClick(item) })
			}
		}
	}
}

@Composable
private fun Placeholder(message: String) {
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.height(230.dp)
			.padding(start = ROW_START.dp),
		contentAlignment = Alignment.CenterStart,
	) {
		Text(
			text = message,
			fontSize = 15.sp,
			color = Color.White.copy(alpha = 0.6f),
		)
	}
}

/** New on Streaming — per-service recent releases, movies or TV. */
@Composable
internal fun StreamingBrowseSection(
	repository: TentacleRepository,
	onItemClick: (DiscoverItem) -> Unit,
) {
	var providers by remember { mutableStateOf<List<StreamingProvider>>(emptyList()) }
	var selectedProvider by remember { mutableStateOf<String?>(null) }
	var type by remember { mutableStateOf("movies") }
	var items by remember { mutableStateOf<List<DiscoverItem>>(emptyList()) }
	var isLoading by remember { mutableStateOf(true) }

	LaunchedEffect(Unit) {
		providers = repository.getStreamingProviders()
		if (selectedProvider == null) selectedProvider = providers.firstOrNull()?.slug
	}
	LaunchedEffect(selectedProvider, type) {
		val provider = selectedProvider ?: return@LaunchedEffect
		isLoading = true
		items = repository.getNewOnStreaming(provider, type)
		isLoading = false
	}

	// Nothing configured (no TMDB) — hide the whole section.
	if (providers.isEmpty()) return

	Column(modifier = Modifier.focusGroup()) {
		BrowseHeader("New on Streaming")
		PillRow {
			items(providers, key = { it.slug }) { provider ->
				FilterPill(
					text = provider.name,
					selected = provider.slug == selectedProvider,
					onClick = { selectedProvider = provider.slug },
				)
			}
		}
		PillRow {
			item {
				FilterPill(text = "Movies", selected = type == "movies") { type = "movies" }
			}
			item {
				FilterPill(text = "TV Shows", selected = type == "series") { type = "series" }
			}
		}
		ContentRow(items, isLoading, "Nothing new on this service right now.", onItemClick)
	}
}

/** Genres — pick a genre, view Top Rated (all-time) or Newly Released. */
@Composable
internal fun GenresBrowseSection(
	repository: TentacleRepository,
	onItemClick: (DiscoverItem) -> Unit,
) {
	var type by remember { mutableStateOf("movies") }
	var mode by remember { mutableStateOf("top_rated") }
	var genres by remember { mutableStateOf<List<DiscoverGenre>>(emptyList()) }
	var selectedGenre by remember { mutableStateOf<Int?>(null) }
	var items by remember { mutableStateOf<List<DiscoverItem>>(emptyList()) }
	var isLoading by remember { mutableStateOf(true) }

	LaunchedEffect(type) {
		genres = repository.getDiscoverGenres(type)
		// Genre ids differ between movies and TV — reset selection if it's gone.
		if (genres.none { it.id == selectedGenre }) selectedGenre = genres.firstOrNull()?.id
	}
	LaunchedEffect(selectedGenre, type, mode) {
		val genre = selectedGenre ?: return@LaunchedEffect
		isLoading = true
		items = repository.getByGenre(genre, type, mode)
		isLoading = false
	}

	if (genres.isEmpty()) return

	Column(modifier = Modifier.focusGroup()) {
		BrowseHeader("Genres")
		PillRow {
			item {
				FilterPill(text = "Movies", selected = type == "movies") { type = "movies" }
			}
			item {
				FilterPill(text = "TV Shows", selected = type == "series") { type = "series" }
			}
		}
		PillRow {
			item {
				FilterPill(text = "Top Rated", selected = mode == "top_rated") { mode = "top_rated" }
			}
			item {
				FilterPill(text = "Newly Released", selected = mode == "new") { mode = "new" }
			}
		}
		PillRow {
			items(genres, key = { it.id }) { genre ->
				FilterPill(
					text = genre.name,
					selected = genre.id == selectedGenre,
					onClick = { selectedGenre = genre.id },
				)
			}
		}
		ContentRow(items, isLoading, "No titles found in this genre.", onItemClick)
	}
}

/** From My Lists — "All" (mixed) plus one tab per list subscription. */
@Composable
internal fun ListsBrowseSection(
	repository: TentacleRepository,
	onItemClick: (DiscoverItem) -> Unit,
) {
	var type by remember { mutableStateOf("movies") }
	var lists by remember { mutableStateOf<List<DiscoverList>>(emptyList()) }
	var selectedList by remember { mutableStateOf("all") }
	var items by remember { mutableStateOf<List<DiscoverItem>>(emptyList()) }
	var isLoading by remember { mutableStateOf(true) }

	LaunchedEffect(type) {
		lists = repository.getDiscoverLists(type)
	}
	LaunchedEffect(selectedList, type) {
		isLoading = true
		items = repository.getListMissing(selectedList, type)
		isLoading = false
	}

	// No list subscriptions — hide the section entirely.
	if (lists.isEmpty()) return

	Column(modifier = Modifier.focusGroup()) {
		BrowseHeader("From My Lists")
		PillRow {
			item {
				FilterPill(text = "Movies", selected = type == "movies") { type = "movies" }
			}
			item {
				FilterPill(text = "TV Shows", selected = type == "series") { type = "series" }
			}
		}
		PillRow {
			item {
				FilterPill(text = "All", selected = selectedList == "all") { selectedList = "all" }
			}
			items(lists, key = { it.id }) { list ->
				FilterPill(
					text = list.name,
					selected = selectedList == list.id.toString(),
					onClick = { selectedList = list.id.toString() },
				)
			}
		}
		ContentRow(items, isLoading, "Nothing missing from this list.", onItemClick)
	}
}
