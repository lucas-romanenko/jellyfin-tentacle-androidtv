package org.jellyfin.androidtv.ui.discover

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import org.jellyfin.androidtv.data.repository.DiscoverItem
import org.jellyfin.androidtv.data.repository.DiscoverSection
import org.jellyfin.androidtv.data.repository.TentacleRepository
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.shared.toolbar.Navbar
import org.jellyfin.androidtv.ui.shared.toolbar.NavbarActiveButton
import org.koin.android.ext.android.inject

class DiscoverFragment : Fragment() {
	private val tentacleRepository by inject<TentacleRepository>()
	private val navigationRepository by inject<NavigationRepository>()

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	) = content {
		JellyfinTheme {
			var sections by remember { mutableStateOf<List<DiscoverSection>>(emptyList()) }
			var isLoading by remember { mutableStateOf(true) }
			var selectedItem by remember { mutableStateOf<DiscoverItem?>(null) }
			val contentFocusRequester = remember { FocusRequester() }

			LaunchedEffect(Unit) {
				sections = tentacleRepository.getDiscoverSections()
				isLoading = false
			}

			// Focus first content row after loading
			LaunchedEffect(isLoading) {
				if (!isLoading && sections.isNotEmpty()) {
					try {
						contentFocusRequester.requestFocus()
					} catch (_: Exception) {
					}
				}
			}

			Column(modifier = Modifier.fillMaxSize()) {
				Navbar(activeButton = NavbarActiveButton.Discover)

				if (isLoading) {
					Box(
						modifier = Modifier.fillMaxSize(),
						contentAlignment = Alignment.Center,
					) {
						Text(
							text = "Loading...",
							fontSize = 18.sp,
							color = Color.White.copy(alpha = 0.7f),
						)
					}
				} else {
					LazyColumn(
						modifier = Modifier
							.fillMaxSize()
							.focusRequester(contentFocusRequester),
						contentPadding = PaddingValues(top = 12.dp, bottom = 48.dp),
						verticalArrangement = Arrangement.spacedBy(24.dp),
					) {
						items(sections, key = { it.id }) { section ->
							DiscoverSectionRow(
								section = section,
								onItemClick = { selectedItem = it },
							)
						}
					}
				}
			}

			// Detail modal
			if (selectedItem != null) {
				DiscoverDetailDialog(
					item = selectedItem!!,
					tentacleRepository = tentacleRepository,
					onDismiss = { selectedItem = null },
					onNavigateToItem = { itemId ->
						selectedItem = null
						navigationRepository.navigate(Destinations.itemDetails(itemId))
					},
				)
			}
		}
	}
}

@Composable
private fun DiscoverSectionRow(
	section: DiscoverSection,
	onItemClick: (DiscoverItem) -> Unit,
) {
	Column(
		modifier = Modifier.focusGroup(),
	) {
		Text(
			text = section.title,
			fontSize = 20.sp,
			fontWeight = FontWeight.Bold,
			color = Color.White,
			modifier = Modifier.padding(start = 48.dp, bottom = 12.dp),
		)

		LazyRow(
			contentPadding = PaddingValues(horizontal = 48.dp),
			horizontalArrangement = Arrangement.spacedBy(16.dp),
		) {
			items(section.items, key = { it.tmdbId }) { item ->
				DiscoverCard(item = item, onClick = { onItemClick(item) })
			}
		}
	}
}
