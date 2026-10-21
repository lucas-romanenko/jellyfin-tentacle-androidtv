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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.repository.DiscoverItem
import org.jellyfin.androidtv.data.repository.TentacleRepository
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.discover.DiscoverCard
import org.jellyfin.androidtv.ui.discover.DiscoverDetailDialog
import org.jellyfin.androidtv.ui.itemhandling.BaseItemDtoBaseRowItem
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.preference.constant.NavbarPosition
import org.jellyfin.androidtv.ui.search.composable.SearchItemCard
import org.jellyfin.androidtv.ui.search.composable.SearchTextInput
import org.jellyfin.androidtv.ui.search.composable.SearchVoiceInput
import org.jellyfin.androidtv.ui.shared.toolbar.NavbarActiveButton
import org.jellyfin.androidtv.ui.shared.toolbar.NavigationLayout
import org.jellyfin.androidtv.ui.shared.toolbar.rememberNavbarPosition
import org.jellyfin.androidtv.util.speech.rememberSpeechRecognizerAvailability
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
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
			val itemLauncher = koinInject<ItemLauncher>()
			val api = koinInject<ApiClient>()
			var query by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
			val textInputFocusRequester = remember { FocusRequester() }
			val resultsFocusRequester = remember { FocusRequester() }
			val speechRecognizerAvailability = rememberSpeechRecognizerAvailability()
			val navbarPosition = rememberNavbarPosition()
			val isSidebar = navbarPosition == NavbarPosition.LEFT

			// TMDB search results
			val tmdbResults by viewModel.tmdbResultsFlow.collectAsState()
			val isTmdbSearching by viewModel.isTmdbSearching.collectAsState()
			// Jellyfin results: Live TV channels (OK tunes) and library items
			val channelResults by viewModel.channelResultsFlow.collectAsState()
			val libraryResults by viewModel.libraryResultsFlow.collectAsState()
			val tmdbUnavailable by tentacleRepository.discoverUnavailable.collectAsState()
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
								// Delay the focus move past the submit key's KeyUp — moving
								// focus during KeyDown lets the KeyUp land on the freshly
								// focused first result card and instantly "click" it.
								scope.launch {
									kotlinx.coroutines.delay(150)
									try {
										resultsFocusRequester.requestFocus()
									} catch (_: Exception) {}
								}
							},
							onNavigateDown = {
								try {
									resultsFocusRequester.requestFocus()
								} catch (_: Exception) {}
							},
							modifier = Modifier
								.weight(1f)
								.focusRequester(textInputFocusRequester),
						)
					}

					// The TMDB search could not reach Tentacle — say so rather than
					// leaving only the library results, as if nothing had matched.
					if (query.text.isNotBlank() && !isTmdbSearching && tmdbResults.isEmpty() && tmdbUnavailable != null) {
						Text(
							text = tmdbUnavailable.orEmpty(),
							fontSize = 14.sp,
							color = Color.White.copy(alpha = 0.5f),
							modifier = Modifier.padding(start = 48.dp, top = 8.dp),
						)
					}

					// Results: Channels, then In your library (both from Jellyfin), then Discover
					// (TMDB / Radarr / Sonarr). Down from the search field goes to the first card
					// of the first row shown.
					val showChannels = channelResults.isNotEmpty()
					val showLibrary = libraryResults.isNotEmpty()
					val showDiscover = tmdbResults.isNotEmpty() || isTmdbSearching
					val openItem: (BaseItemDto) -> Unit = { item ->
						itemLauncher.launch(BaseItemDtoBaseRowItem(item), null, requireContext())
					}

					Column(
						modifier = Modifier
							.verticalScroll(rememberScrollState())
							.padding(bottom = 27.dp),
					) {
						if (showChannels) {
							SearchJellyfinRow(
								title = stringResource(R.string.channels),
								items = channelResults,
								api = api,
								firstCardModifier = Modifier.focusRequester(resultsFocusRequester),
								onClick = openItem,
							)
						}

						if (showLibrary) {
							SearchJellyfinRow(
								title = stringResource(R.string.search_row_library),
								items = libraryResults,
								api = api,
								firstCardModifier = if (!showChannels) Modifier.focusRequester(resultsFocusRequester) else Modifier,
								onClick = openItem,
							)
						}

						if (showDiscover) {
							Column(
								modifier = Modifier
									.focusGroup()
									.padding(top = 8.dp),
							) {
								if (showChannels || showLibrary) SearchRowTitle(stringResource(R.string.lbl_add_media))
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
										// Key MUST be unique per item or Compose crashes. Prefixing with the
										// list index guarantees uniqueness even when results share ids —
										// e.g. multiple unidentified items (tmdbId=0 && tvdbId=0) or a movie
										// and series with the same numeric TMDB id.
										itemsIndexed(
											tmdbResults,
											key = { index, it -> "$index:${it.mediaType}:${it.tmdbId}:${it.tvdbId}" },
										) { index, item ->
											DiscoverCard(
												item = item,
												// The focus hop from the search input targets the first
												// card directly — requesting focus on the container
												// Column throws (it isn't focusable) and lands nowhere.
												modifier = if (index == 0 && !showChannels && !showLibrary) Modifier.focusRequester(resultsFocusRequester) else Modifier,
												onClick = {
													if (item.inLibrary) {
														scope.launch {
															val itemId = tentacleRepository.resolveOwnedItem(
																item.mediaType, item.tmdbId, item.title, item.year
															)
															if (itemId != null) {
																navigationRepository.navigate(Destinations.itemDetails(itemId))
															} else {
																selectedItem = item // open the detail dialog instead of doing nothing
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

	@Composable
	private fun SearchRowTitle(title: String) {
		Text(
			text = title,
			fontSize = 16.sp,
			fontWeight = FontWeight.SemiBold,
			color = Color.White.copy(alpha = 0.85f),
			modifier = Modifier.padding(start = 48.dp, bottom = 6.dp),
		)
	}

	@Composable
	private fun SearchJellyfinRow(
		title: String,
		items: List<BaseItemDto>,
		api: ApiClient,
		firstCardModifier: Modifier,
		onClick: (BaseItemDto) -> Unit,
	) {
		Column(
			modifier = Modifier
				.focusGroup()
				.padding(top = 8.dp),
		) {
			SearchRowTitle(title)
			LazyRow(
				contentPadding = PaddingValues(horizontal = 48.dp),
				horizontalArrangement = Arrangement.spacedBy(12.dp),
				modifier = Modifier.fillMaxWidth(),
			) {
				itemsIndexed(items, key = { index, it -> "$index:${it.id}" }) { index, item ->
					SearchItemCard(
						item = item,
						api = api,
						onClick = { onClick(item) },
						modifier = if (index == 0) firstCardModifier else Modifier,
					)
				}
			}
		}
	}
}
