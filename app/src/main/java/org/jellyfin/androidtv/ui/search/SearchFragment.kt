package org.jellyfin.androidtv.ui.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.repository.DiscoverItem
import org.jellyfin.androidtv.data.repository.TentacleRepository
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.discover.DiscoverCard
import org.jellyfin.androidtv.ui.discover.DiscoverDetailDialog
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.preference.constant.NavbarPosition
import org.jellyfin.androidtv.ui.search.composable.SearchTextInput
import org.jellyfin.androidtv.ui.search.composable.SearchVoiceInput
import org.jellyfin.androidtv.ui.shared.toolbar.NavbarActiveButton
import org.jellyfin.androidtv.ui.shared.toolbar.NavigationLayout
import org.jellyfin.androidtv.ui.shared.toolbar.rememberNavbarPosition
import org.jellyfin.androidtv.util.speech.rememberSpeechRecognizerAvailability
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

class SearchFragment : Fragment() {
	companion object {
		const val EXTRA_QUERY = "query"
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	) = content {
		JellyfinTheme {
			val viewModel = koinViewModel<SearchViewModel>()
			val tentacleRepository = koinInject<TentacleRepository>()
			val navigationRepository = koinInject<NavigationRepository>()
			val dataRefreshService = koinInject<DataRefreshService>()
			var query by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
			val textInputFocusRequester = remember { FocusRequester() }
			val resultsFocusRequester = remember { FocusRequester() }
			val speechRecognizerAvailability = rememberSpeechRecognizerAvailability()
			val navbarPosition = rememberNavbarPosition()
			val isSidebar = navbarPosition == NavbarPosition.LEFT

			// TMDB search results
			val tmdbResults by viewModel.tmdbResultsFlow.collectAsState()
			val isTmdbSearching by viewModel.isTmdbSearching.collectAsState()
			var selectedItem by remember { mutableStateOf<DiscoverItem?>(null) }
			val scope = rememberCoroutineScope()

			// Re-search when returning after a deletion so "In Library" badges update
			val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
			DisposableEffect(lifecycleOwner) {
				val observer = LifecycleEventObserver { _, event ->
					if (event == Lifecycle.Event.ON_RESUME && dataRefreshService.lastDeletedItemId != null) {
						dataRefreshService.lastDeletedItemId = null
						viewModel.forceRefresh()
					}
				}
				lifecycleOwner.lifecycle.addObserver(observer)
				onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
			}

			LaunchedEffect(Unit) {
				val extraQuery = arguments?.getString(EXTRA_QUERY)
				if (!extraQuery.isNullOrBlank()) {
					query = query.copy(text = extraQuery)
					viewModel.searchImmediately(extraQuery)
					resultsFocusRequester.requestFocus()
				} else {
					textInputFocusRequester.requestFocus()
				}
			}

			NavigationLayout(NavbarActiveButton.Search) {
				Column {
					Row(
						horizontalArrangement = Arrangement.spacedBy(12.dp),
						verticalAlignment = Alignment.CenterVertically,
						modifier = Modifier
							.focusRestorer()
							.focusGroup()
							.padding(start = 48.dp, end = if (isSidebar) 80.dp else 48.dp)
							.padding(top = if (isSidebar) 20.dp else 0.dp)
					) {
						if (speechRecognizerAvailability) {
							SearchVoiceInput(
								onQueryChange = { query = query.copy(text = it) },
								onQuerySubmit = {
									viewModel.searchImmediately(query.text)
									try {
										resultsFocusRequester.requestFocus()
									} catch (_: Exception) {}
								}
							)
						}

						SearchTextInput(
							query = query.text,
							onQueryChange = {
								query = query.copy(text = it)
								viewModel.searchDebounced(query.text)
							},
							onQuerySubmit = {
								viewModel.searchImmediately(query.text)
								try {
									resultsFocusRequester.requestFocus()
								} catch (_: Exception) {}
							},
							modifier = Modifier
								.weight(1f)
								.focusRequester(textInputFocusRequester),
						)
					}

					// TMDB search results
					if (tmdbResults.isNotEmpty() || isTmdbSearching) {
						Column(
							modifier = Modifier
								.focusGroup()
								.focusRequester(resultsFocusRequester)
								.padding(top = 8.dp),
						) {
							if (isTmdbSearching) {
								Text(
									text = "Searching...",
									fontSize = 14.sp,
									color = Color.White.copy(alpha = 0.5f),
									modifier = Modifier.padding(start = 48.dp),
								)
							} else {
								LazyRow(
									contentPadding = PaddingValues(horizontal = 48.dp),
									horizontalArrangement = Arrangement.spacedBy(12.dp),
									modifier = Modifier.fillMaxWidth(),
								) {
									items(tmdbResults, key = { it.tmdbId }) { item ->
										DiscoverCard(
											item = item,
											onClick = {
												if (item.inLibrary) {
													scope.launch {
														val itemId = tentacleRepository.findJellyfinItem(
															item.title, item.year, item.mediaType
														)
														if (itemId != null) {
															navigationRepository.navigate(Destinations.itemDetails(itemId))
														}
													}
												} else {
													selectedItem = item
												}
											},
										)
									}
								}
							}
						}
					}
				}
			}

			// Detail/add modal for TMDB results
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
