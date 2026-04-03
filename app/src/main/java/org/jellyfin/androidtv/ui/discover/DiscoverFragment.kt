package org.jellyfin.androidtv.ui.discover

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import android.content.Context
import org.jellyfin.androidtv.data.repository.DiscoverDetail
import org.jellyfin.androidtv.data.repository.DiscoverItem
import org.jellyfin.androidtv.data.repository.DiscoverSection
import org.jellyfin.androidtv.data.repository.QualityProfile
import org.jellyfin.androidtv.data.repository.SonarrEpisodesResponse
import org.jellyfin.androidtv.data.repository.TentacleRepository
import org.jellyfin.androidtv.ui.base.JellyfinTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.button.ButtonDefaults
import org.jellyfin.androidtv.ui.search.composable.SearchTextInput
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbar
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.shared.toolbar.MainToolbarActiveButton
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

			// Search state
			var searchQuery by rememberSaveable { mutableStateOf("") }
			var searchResults by remember { mutableStateOf<List<DiscoverItem>>(emptyList()) }
			var isSearching by remember { mutableStateOf(false) }
			var hasSearched by remember { mutableStateOf(false) }
			val scope = rememberCoroutineScope()
			val searchInputFocusRequester = remember { FocusRequester() }
			val contentFocusRequester = remember { FocusRequester() }

			LaunchedEffect(Unit) {
				sections = tentacleRepository.getDiscoverSections()
				isLoading = false
			}

			// Focus first content row after loading instead of search
			LaunchedEffect(isLoading) {
				if (!isLoading && sections.isNotEmpty()) {
					try {
						contentFocusRequester.requestFocus()
					} catch (_: Exception) {
					}
				}
			}

			Column(modifier = Modifier.fillMaxSize()) {
				MainToolbar(MainToolbarActiveButton.Discover)

				// Search bar
				Row(
					verticalAlignment = Alignment.CenterVertically,
					modifier = Modifier
						.focusGroup()
						.padding(horizontal = 48.dp, vertical = 12.dp),
				) {
					SearchTextInput(
						query = searchQuery,
						onQueryChange = { searchQuery = it },
						onQuerySubmit = {
							if (searchQuery.isNotBlank()) {
								isSearching = true
								hasSearched = true
								scope.launch {
									searchResults = tentacleRepository.searchDiscover(searchQuery)
									isSearching = false
									try {
										contentFocusRequester.requestFocus()
									} catch (_: Exception) {
									}
								}
							}
						},
						placeholder = "Search for new movies and shows",
						modifier = Modifier
							.weight(1f)
							.focusRequester(searchInputFocusRequester),
					)

					if (hasSearched) {
						Spacer(modifier = Modifier.width(12.dp))
						Button(
							onClick = {
								searchQuery = ""
								searchResults = emptyList()
								hasSearched = false
								try {
									searchInputFocusRequester.requestFocus()
								} catch (_: Exception) {
								}
							},
							colors = ButtonDefaults.colors(),
						) {
							Text("Clear")
						}
					}
				}

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
						contentPadding = PaddingValues(bottom = 48.dp),
						verticalArrangement = Arrangement.spacedBy(24.dp),
					) {
						// Search results section
						if (hasSearched) {
							item(key = "search_results") {
								DiscoverSearchResultsRow(
									results = searchResults,
									isSearching = isSearching,
									onItemClick = { selectedItem = it },
								)
							}
						}

						// Regular discover sections
						if (!hasSearched || searchResults.isEmpty()) {
							items(sections, key = { it.id }) { section ->
								DiscoverSectionRow(
									section = section,
									onItemClick = { selectedItem = it },
								)
							}
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

private const val TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w342"
private const val TMDB_BACKDROP_BASE = "https://image.tmdb.org/t/p/w1280"

@Composable
private fun DiscoverSearchResultsRow(
	results: List<DiscoverItem>,
	isSearching: Boolean,
	onItemClick: (DiscoverItem) -> Unit,
) {
	Column(
		modifier = Modifier.focusGroup(),
	) {
		Text(
			text = "Search Results",
			fontSize = 20.sp,
			fontWeight = FontWeight.Bold,
			color = Color.White,
			modifier = Modifier.padding(start = 48.dp, bottom = 12.dp),
		)

		when {
			isSearching -> {
				Text(
					text = "Searching...",
					fontSize = 14.sp,
					color = Color.White.copy(alpha = 0.5f),
					modifier = Modifier.padding(start = 48.dp),
				)
			}
			results.isEmpty() -> {
				Text(
					text = "No results found",
					fontSize = 14.sp,
					color = Color.White.copy(alpha = 0.5f),
					modifier = Modifier.padding(start = 48.dp),
				)
			}
			else -> {
				LazyRow(
					contentPadding = PaddingValues(horizontal = 48.dp),
					horizontalArrangement = Arrangement.spacedBy(16.dp),
				) {
					items(results, key = { it.tmdbId }) { item ->
						DiscoverCard(item = item, onClick = { onItemClick(item) })
					}
				}
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


@Composable
private fun DiscoverCard(
	item: DiscoverItem,
	onClick: () -> Unit,
) {
	var isFocused by remember { mutableStateOf(false) }

	Column(
		modifier = Modifier
			.width(150.dp)
			.onFocusChanged { isFocused = it.isFocused }
			.focusable()
			.onKeyEvent { event ->
				if (event.type == KeyEventType.KeyUp &&
					(event.key == Key.Enter || event.key == Key.DirectionCenter)
				) {
					onClick()
					true
				} else {
					false
				}
			}
			.clickable { onClick() }
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

			// Media type badge
			Box(
				modifier = Modifier
					.align(Alignment.TopEnd)
					.padding(6.dp)
					.background(
						color = if (item.mediaType == "movie") Color(0xCC1E88E5) else Color(0xCC7B1FA2),
						shape = RoundedCornerShape(4.dp),
					)
					.padding(horizontal = 6.dp, vertical = 2.dp),
			) {
				Text(
					text = if (item.mediaType == "movie") "Movie" else "Series",
					fontSize = 10.sp,
					color = Color.White,
				)
			}

			// In-library badge
			if (item.inLibrary) {
				Box(
					modifier = Modifier
						.align(Alignment.TopStart)
						.padding(6.dp)
						.background(
							color = Color(0xCC4CAF50),
							shape = RoundedCornerShape(4.dp),
						)
						.padding(horizontal = 6.dp, vertical = 2.dp),
				) {
					Text(
						text = "In Library",
						fontSize = 10.sp,
						color = Color.White,
					)
				}
			}

			// Focus highlight overlay
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

private data class MonitorOption(val key: String, val label: String)

private val MONITOR_OPTIONS = listOf(
	MonitorOption("all", "All Episodes"),
	MonitorOption("firstSeason", "First Season"),
	MonitorOption("lastSeason", "Last Season"),
	MonitorOption("pilot", "Pilot"),
	MonitorOption("pick", "Pick Episodes"),
)

@Composable
private fun DiscoverDetailDialog(
	item: DiscoverItem,
	tentacleRepository: TentacleRepository,
	onDismiss: () -> Unit,
	onNavigateToItem: (java.util.UUID) -> Unit = {},
) {
	var detail by remember { mutableStateOf<DiscoverDetail?>(null) }
	var isLoadingDetail by remember { mutableStateOf(true) }
	var addStatus by remember { mutableStateOf<String?>(null) }
	var isAdding by remember { mutableStateOf(false) }
	val scope = rememberCoroutineScope()
	val buttonFocusRequester = remember { FocusRequester() }
	val context = LocalContext.current

	// Quality profiles
	var qualityProfiles by remember { mutableStateOf<List<QualityProfile>>(emptyList()) }
	var selectedProfileId by remember { mutableStateOf<Int?>(null) }

	// Sonarr monitoring option (for series not in library)
	var monitorOptionIndex by remember { mutableStateOf(0) }

	// Episode picker state
	var showEpisodePicker by remember { mutableStateOf(false) }
	var episodePickerMode by remember { mutableStateOf(EpisodePickerMode.ADD_NEW) }

	// Following state (for series in library)
	var isFollowing by remember { mutableStateOf<Boolean?>(null) }
	var isTogglingFollow by remember { mutableStateOf(false) }

	// Sonarr state (for series in library)
	var sonarrState by remember { mutableStateOf<SonarrEpisodesResponse?>(null) }

	// Load detail + quality profiles
	LaunchedEffect(item.tmdbId) {
		detail = tentacleRepository.getDiscoverDetail(item.mediaType, item.tmdbId)
		isLoadingDetail = false

		// Set following state from detail enrichment
		detail?.following?.let { isFollowing = it }

		// Fetch profiles based on media type
		val profiles = if (item.mediaType == "movie") {
			tentacleRepository.getRadarrProfiles()
		} else {
			tentacleRepository.getSonarrProfiles()
		}
		qualityProfiles = profiles

		// Restore last selected profile from prefs
		val prefKey = if (item.mediaType == "movie") "tentacle_radarr_profile" else "tentacle_sonarr_profile"
		val prefs = context.getSharedPreferences("tentacle", Context.MODE_PRIVATE)
		val savedId = prefs.getInt(prefKey, -1)
		selectedProfileId = if (savedId != -1 && profiles.any { it.id == savedId }) savedId
			else profiles.firstOrNull()?.id

		// For in-library series, check if in Sonarr
		if (item.inLibrary && item.mediaType == "series") {
			sonarrState = tentacleRepository.getSonarrEpisodes(item.tmdbId)
		}
	}

	// Focus the action button once detail loads
	LaunchedEffect(isLoadingDetail) {
		if (!isLoadingDetail) {
			try {
				buttonFocusRequester.requestFocus()
			} catch (_: Exception) {
			}
		}
	}

	Dialog(
		onDismissRequest = {
			if (showEpisodePicker) showEpisodePicker = false else onDismiss()
		},
		properties = DialogProperties(
			usePlatformDefaultWidth = false,
		),
	) {
		Box(
			modifier = Modifier
				.fillMaxSize()
				.background(Color.Black.copy(alpha = 0.5f))
				.clickable(
					interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
					indication = null,
				) {
					if (showEpisodePicker) showEpisodePicker = false else onDismiss()
				}
				.onKeyEvent { event ->
					if (event.type == KeyEventType.KeyUp && event.key == Key.Back) {
						if (showEpisodePicker) {
							showEpisodePicker = false
						} else {
							onDismiss()
						}
						true
					} else {
						false
					}
				},
			contentAlignment = Alignment.Center,
		) {
			Box(
				modifier = Modifier
					.fillMaxWidth(0.75f)
					.fillMaxHeight(0.85f)
					.clip(RoundedCornerShape(16.dp))
					.background(Color(0xFF1a1a2e))
					.clickable(
					interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
					indication = null,
				) { /* consume click to prevent dismiss */ }
			) {
				if (showEpisodePicker) {
					// Episode picker replaces detail content within the same dialog
					EpisodePickerContent(
						tmdbId = item.tmdbId,
						title = detail?.title ?: item.title,
						mode = episodePickerMode,
						qualityProfileId = selectedProfileId,
						tentacleRepository = tentacleRepository,
						onDismiss = { showEpisodePicker = false },
						onComplete = { message ->
							showEpisodePicker = false
							addStatus = message
						},
					)
				} else if (isLoadingDetail) {
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
					val d = detail
					Column(modifier = Modifier.fillMaxSize()) {
						// Backdrop image
						Box(
							modifier = Modifier
								.fillMaxWidth()
								.height(280.dp)
						) {
							val backdropUrl = d?.backdropPath ?: item.backdropPath
							if (backdropUrl != null) {
								AsyncImage(
									model = "$TMDB_BACKDROP_BASE$backdropUrl",
									contentDescription = null,
									contentScale = ContentScale.Crop,
									modifier = Modifier.fillMaxSize(),
								)
							}

							// Gradient overlay at bottom
							Box(
								modifier = Modifier
									.fillMaxWidth()
									.height(120.dp)
									.align(Alignment.BottomCenter)
									.background(
										Brush.verticalGradient(
											colors = listOf(Color.Transparent, Color(0xFF1a1a2e)),
										)
									)
							)

							// Title overlay
							Column(
								modifier = Modifier
									.align(Alignment.BottomStart)
									.padding(start = 32.dp, bottom = 16.dp)
							) {
								Text(
									text = d?.title ?: item.title,
									fontSize = 28.sp,
									fontWeight = FontWeight.Bold,
									color = Color.White,
								)
								Row(
									horizontalArrangement = Arrangement.spacedBy(12.dp),
									verticalAlignment = Alignment.CenterVertically,
								) {
									val year = d?.year ?: item.year
									if (!year.isNullOrBlank()) {
										Text(text = year, fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f))
									}
									if (d?.runtime != null && d.runtime > 0) {
										Text(
											text = "${d.runtime} min",
											fontSize = 14.sp,
											color = Color.White.copy(alpha = 0.7f),
										)
									}
									if (d != null && d.rating > 0) {
										Text(
											text = "${d.rating}/10",
											fontSize = 14.sp,
											color = Color(0xFFFFC107),
											fontWeight = FontWeight.Bold,
										)
									}
									// Media type badge
									Box(
										modifier = Modifier
											.background(
												color = if (item.mediaType == "movie") Color(0xCC1E88E5) else Color(0xCC7B1FA2),
												shape = RoundedCornerShape(4.dp),
											)
											.padding(horizontal = 8.dp, vertical = 2.dp),
									) {
										Text(
											text = if (item.mediaType == "movie") "Movie" else "Series",
											fontSize = 12.sp,
											color = Color.White,
										)
									}
								}
							}
						}

						// Content area
						Column(
							modifier = Modifier
								.fillMaxSize()
								.verticalScroll(rememberScrollState())
								.padding(horizontal = 32.dp, vertical = 16.dp),
						) {
							// Genres
							if (d != null && d.genres.isNotEmpty()) {
								Text(
									text = d.genres.joinToString(" • "),
									fontSize = 13.sp,
									color = Color.White.copy(alpha = 0.6f),
								)
								Spacer(modifier = Modifier.height(12.dp))
							}

							// Tagline
							if (d != null && d.tagline.isNotBlank()) {
								Text(
									text = "\"${d.tagline}\"",
									fontSize = 14.sp,
									color = Color.White.copy(alpha = 0.8f),
									fontWeight = FontWeight.Medium,
								)
								Spacer(modifier = Modifier.height(8.dp))
							}

							// Overview
							Text(
								text = d?.overview ?: item.overview,
								fontSize = 14.sp,
								color = Color.White.copy(alpha = 0.8f),
								lineHeight = 22.sp,
							)

							Spacer(modifier = Modifier.height(16.dp))

							// Cast
							if (d != null && d.cast.isNotEmpty()) {
								Text(
									text = "Cast",
									fontSize = 15.sp,
									fontWeight = FontWeight.Bold,
									color = Color.White,
								)
								Spacer(modifier = Modifier.height(4.dp))
								Text(
									text = d.cast.take(6).joinToString(", ") { it.name },
									fontSize = 13.sp,
									color = Color.White.copy(alpha = 0.6f),
								)
								Spacer(modifier = Modifier.height(12.dp))
							}

							// Directors
							if (d != null && d.directors.isNotEmpty()) {
								Text(
									text = "Director",
									fontSize = 15.sp,
									fontWeight = FontWeight.Bold,
									color = Color.White,
								)
								Spacer(modifier = Modifier.height(4.dp))
								Text(
									text = d.directors.joinToString(", "),
									fontSize = 13.sp,
									color = Color.White.copy(alpha = 0.6f),
								)
								Spacer(modifier = Modifier.height(16.dp))
							}

							// Action buttons
							Row(
								horizontalArrangement = Arrangement.spacedBy(12.dp),
							) {
								if (item.inLibrary) {
									// --- In-library actions ---

									// View in Library button
									Button(
										onClick = {
											scope.launch {
												val itemId = tentacleRepository.findJellyfinItem(
													item.title, item.year, item.mediaType
												)
												if (itemId != null) {
													onNavigateToItem(itemId)
												} else {
													addStatus = "Could not find item in library"
												}
											}
										},
										colors = ButtonDefaults.colors(
											containerColor = Color(0xFF4CAF50),
											contentColor = Color.White,
										),
										modifier = Modifier.focusRequester(buttonFocusRequester),
									) {
										Text(text = "View in Library", fontSize = 14.sp)
									}

									// Series-specific in-library actions
									if (item.mediaType == "series") {
										// Follow toggle (hide for ended/canceled)
										val seriesStatus = d?.seriesStatus ?: ""
										val isEndedOrCanceled = seriesStatus.equals("Ended", ignoreCase = true) ||
											seriesStatus.equals("Canceled", ignoreCase = true)

										if (!isEndedOrCanceled && isFollowing != null) {
											Button(
												onClick = {
													if (!isTogglingFollow) {
														isTogglingFollow = true
														scope.launch {
															val result = tentacleRepository.toggleFollow(
																item.tmdbId, !(isFollowing ?: false)
															)
															if (result.success) {
																isFollowing = result.following
															}
															isTogglingFollow = false
														}
													}
												},
												colors = ButtonDefaults.colors(
													containerColor = if (isFollowing == true) Color(0xFF7C6AE8) else Color(0xFF374151),
													contentColor = Color.White,
												),
											) {
												Text(
													text = when {
														isTogglingFollow -> "..."
														isFollowing == true -> "Following"
														else -> "Follow"
													},
													fontSize = 14.sp,
												)
											}
										}

										// Episodes button (Manage or Download More)
										val inSonarr = sonarrState?.inSonarr == true
										Button(
											onClick = {
												episodePickerMode = if (inSonarr) EpisodePickerMode.MANAGE
													else EpisodePickerMode.DOWNLOAD_MORE
												showEpisodePicker = true
											},
											colors = ButtonDefaults.colors(
												containerColor = Color(0xFF7B1FA2),
												contentColor = Color.White,
											),
										) {
											Text(
												text = if (inSonarr) "Manage Episodes" else "Download More",
												fontSize = 14.sp,
											)
										}
									}
								}

								if (!item.inLibrary) {
									// --- Not-in-library actions ---

									// Quality profile cycling button
									if (qualityProfiles.size > 1) {
										val selectedName = qualityProfiles.find { it.id == selectedProfileId }?.name ?: "Default"
										Button(
											onClick = {
												val currentIndex = qualityProfiles.indexOfFirst { it.id == selectedProfileId }
												val nextIndex = (currentIndex + 1) % qualityProfiles.size
												val nextProfile = qualityProfiles[nextIndex]
												selectedProfileId = nextProfile.id
												val prefKey = if (item.mediaType == "movie") "tentacle_radarr_profile" else "tentacle_sonarr_profile"
												context.getSharedPreferences("tentacle", Context.MODE_PRIVATE)
													.edit().putInt(prefKey, nextProfile.id).apply()
											},
											colors = ButtonDefaults.colors(
												containerColor = Color(0xFF374151),
												contentColor = Color.White,
											),
										) {
											Text(text = selectedName, fontSize = 14.sp)
										}
									}

									if (item.mediaType == "movie") {
										// Add to Radarr (unchanged)
										Button(
											onClick = {
												if (!isAdding) {
													isAdding = true
													addStatus = null
													scope.launch {
														val result = tentacleRepository.addToRadarr(item.tmdbId, selectedProfileId)
														isAdding = false
														addStatus = when {
															result.error != null -> "Error: ${result.error}"
															result.added > 0 -> "Added to Radarr!"
															result.alreadyExists > 0 -> "Already in Radarr"
															result.failed > 0 -> "Radarr rejected the request"
															else -> "Failed to add"
														}
													}
												}
											},
											colors = ButtonDefaults.colors(
												containerColor = Color(0xFF2196F3),
												contentColor = Color.White,
											),
											modifier = Modifier.focusRequester(buttonFocusRequester),
										) {
											Text(
												text = if (isAdding) "Adding..." else "Add to Radarr",
												fontSize = 14.sp,
											)
										}
									} else {
										// Monitoring option cycling button
										val currentOption = MONITOR_OPTIONS[monitorOptionIndex]
										Button(
											onClick = {
												monitorOptionIndex = (monitorOptionIndex + 1) % MONITOR_OPTIONS.size
											},
											colors = ButtonDefaults.colors(
												containerColor = Color(0xFF374151),
												contentColor = Color.White,
											),
										) {
											Text(text = "Episodes: ${currentOption.label}", fontSize = 14.sp)
										}

										// Add to Sonarr button
										Button(
											onClick = {
												if (!isAdding) {
													val option = MONITOR_OPTIONS[monitorOptionIndex]
													if (option.key == "pick") {
														// Open episode picker
														episodePickerMode = EpisodePickerMode.ADD_NEW
														showEpisodePicker = true
													} else {
														isAdding = true
														addStatus = null
														scope.launch {
															val result = tentacleRepository.addToSonarrWithEpisodes(
																tmdbId = item.tmdbId,
																qualityProfileId = selectedProfileId,
																monitor = option.key,
															)
															isAdding = false
															addStatus = when {
																result.error != null -> "Error: ${result.error}"
																result.added > 0 -> "Added to Sonarr!"
																result.alreadyExists > 0 -> "Already in Sonarr"
																result.failed > 0 -> "Sonarr rejected the request"
																else -> "Failed to add"
															}
														}
													}
												}
											},
											colors = ButtonDefaults.colors(
												containerColor = Color(0xFF7B1FA2),
												contentColor = Color.White,
											),
											modifier = Modifier.focusRequester(buttonFocusRequester),
										) {
											Text(
												text = if (isAdding) "Adding..." else "Add to Sonarr",
												fontSize = 14.sp,
											)
										}
									}
								}
							}

							// Status message
							if (addStatus != null) {
								Spacer(modifier = Modifier.height(8.dp))
								Text(
									text = addStatus!!,
									fontSize = 13.sp,
									color = if (addStatus!!.startsWith("Error") || addStatus!!.startsWith("Failed"))
										Color(0xFFEF5350)
									else Color(0xFF4CAF50),
									fontWeight = FontWeight.Bold,
								)
							}

							Spacer(modifier = Modifier.height(24.dp))
						}
					}
				}
			}
		}
	}
}
