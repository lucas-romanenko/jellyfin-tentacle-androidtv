package org.jellyfin.androidtv.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.data.repository.SelectedEpisode
import org.jellyfin.androidtv.data.repository.SonarrEpisode
import org.jellyfin.androidtv.data.repository.SonarrEpisodesResponse
import org.jellyfin.androidtv.data.repository.TentacleRepository
import org.jellyfin.androidtv.data.repository.TmdbEpisode
import org.jellyfin.androidtv.data.repository.TmdbSeason
import org.jellyfin.androidtv.data.repository.VodEpisodesResponse
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.base.button.ButtonDefaults
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class EpisodePickerMode {
	ADD_NEW,
	DOWNLOAD_MORE,
	MANAGE,
}

/**
 * Episode picker content — rendered inside the parent detail dialog.
 * Used for "Pick Episodes" (Add to Sonarr), "Download More", and "Manage Episodes".
 */
@Composable
fun EpisodePickerContent(
	tmdbId: Int,
	title: String,
	mode: EpisodePickerMode,
	qualityProfileId: Int?,
	autoFollow: Boolean = true,
	tentacleRepository: TentacleRepository,
	onDismiss: () -> Unit,
	onComplete: (message: String) -> Unit,
) {
	var isLoading by remember { mutableStateOf(true) }
	var isSubmitting by remember { mutableStateOf(false) }
	var seasons by remember { mutableStateOf<List<TmdbSeason>>(emptyList()) }
	var seasonEpisodes by remember { mutableStateOf<Map<Int, List<TmdbEpisode>>>(emptyMap()) }
	var sonarrData by remember { mutableStateOf(SonarrEpisodesResponse()) }
	var vodData by remember { mutableStateOf(VodEpisodesResponse()) }
	val expandedSeasons = remember { mutableStateMapOf<Int, Boolean>() }
	val selectedEpisodes = remember { mutableStateMapOf<String, Boolean>() } // "s:e" -> selected
	val scope = rememberCoroutineScope()
	val listFocusRequester = remember { FocusRequester() }

	// Load all data in parallel
	LaunchedEffect(tmdbId) {
		val seasonsResult = tentacleRepository.getSeasons(tmdbId)
		seasons = seasonsResult?.seasons?.filter { (it.seasonNumber ?: 0) > 0 } ?: emptyList()

		if (seasons.isNotEmpty()) {
			// Fetch episodes for all seasons + VOD/Sonarr data in parallel
			val episodeJobs = seasons.map { season ->
				scope.async {
					val eps = tentacleRepository.getSeasonEpisodes(tmdbId, season.seasonNumber ?: 0)
					(season.seasonNumber ?: 0) to eps
				}
			}
			val sonarrJob = scope.async { tentacleRepository.getSonarrEpisodes(tmdbId) }
			val vodJob = scope.async { tentacleRepository.getVodEpisodes(tmdbId) }

			val episodeResults = episodeJobs.awaitAll()
			seasonEpisodes = episodeResults.toMap()
			sonarrData = sonarrJob.await()
			vodData = vodJob.await()

			// Pre-select monitored episodes in manage mode
			if (mode == EpisodePickerMode.MANAGE && sonarrData.inSonarr) {
				sonarrData.episodes.filter { it.monitored }.forEach { ep ->
					selectedEpisodes["${ep.seasonNumber}:${ep.episodeNumber}"] = true
				}
			}
		}

		isLoading = false
	}

	// Focus list after loading
	LaunchedEffect(isLoading) {
		if (!isLoading) {
			try { listFocusRequester.requestFocus() } catch (_: Exception) {}
		}
	}

	val today = remember { LocalDate.now() }

	Column(modifier = Modifier.fillMaxSize()) {
					// Header
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.background(Color(0xFF252547))
							.padding(horizontal = 24.dp, vertical = 16.dp),
						verticalAlignment = Alignment.CenterVertically,
						horizontalArrangement = Arrangement.SpaceBetween,
					) {
						Column(modifier = Modifier.weight(1f)) {
							Text(
								text = when (mode) {
									EpisodePickerMode.ADD_NEW -> "Pick Episodes"
									EpisodePickerMode.DOWNLOAD_MORE -> "Download More Episodes"
									EpisodePickerMode.MANAGE -> "Manage Episodes"
								},
								fontSize = 20.sp,
								fontWeight = FontWeight.Bold,
								color = Color.White,
							)
							Text(
								text = title,
								fontSize = 14.sp,
								color = Color.White.copy(alpha = 0.6f),
							)
						}

						val selectedCount = selectedEpisodes.count { it.value }
						Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
							Button(
								onClick = { onDismiss() },
								colors = ButtonDefaults.colors(
									containerColor = Color(0xFF374151),
									contentColor = Color.White,
								),
							) { Text("Cancel", fontSize = 14.sp) }

							Button(
								onClick = {
									if (!isSubmitting && selectedCount > 0) {
										isSubmitting = true
										scope.launch {
											val selected = selectedEpisodes
												.filter { it.value }
												.map { entry ->
													val parts = entry.key.split(":")
													SelectedEpisode(parts[0].toInt(), parts[1].toInt())
												}

											val message = when (mode) {
												EpisodePickerMode.ADD_NEW, EpisodePickerMode.DOWNLOAD_MORE -> {
													val result = tentacleRepository.addToSonarrWithEpisodes(
														tmdbId = tmdbId,
														qualityProfileId = qualityProfileId,
														monitor = "none",
														selectedEpisodes = selected,
														autoFollow = autoFollow,
													)
													when {
														result.error != null -> "Error: ${result.error}"
														result.added > 0 -> "Added $selectedCount episodes to Sonarr"
														result.alreadyExists > 0 -> "Already in Sonarr"
														else -> "Failed to add"
													}
												}
												EpisodePickerMode.MANAGE -> {
													val result = tentacleRepository.manageEpisodes(tmdbId, selected)
													if (result.success) {
														"Monitoring ${result.monitored} episodes" +
															if (result.searching > 0) ", searching ${result.searching}" else ""
													} else "Failed to update"
												}
											}
											onComplete(message)
										}
									}
								},
								colors = ButtonDefaults.colors(
									containerColor = if (selectedCount > 0) Color(0xFF7C6AE8) else Color(0xFF374151),
									contentColor = Color.White,
								),
							) {
								Text(
									text = if (isSubmitting) "Saving..."
									else if (selectedCount > 0) "Confirm ($selectedCount)"
									else "Select episodes",
									fontSize = 14.sp,
								)
							}
						}
					}

					if (isLoading) {
						Box(
							modifier = Modifier.fillMaxSize(),
							contentAlignment = Alignment.Center,
						) {
							Text("Loading episodes...", fontSize = 16.sp, color = Color.White.copy(alpha = 0.7f))
						}
					} else if (seasons.isEmpty()) {
						Box(
							modifier = Modifier.fillMaxSize(),
							contentAlignment = Alignment.Center,
						) {
							Text("No seasons found", fontSize = 16.sp, color = Color.White.copy(alpha = 0.5f))
						}
					} else {
						// Build flat list of season headers + episodes
						val listItems = remember(seasons, seasonEpisodes, expandedSeasons.toMap()) {
							buildList {
								for (season in seasons) {
									val sNum = season.seasonNumber ?: continue
									add(PickerListItem.SeasonHeader(season, sNum))
									if (expandedSeasons[sNum] == true) {
										val eps = seasonEpisodes[sNum] ?: emptyList()
										eps.forEach { ep ->
											add(PickerListItem.EpisodeRow(ep, sNum))
										}
									}
								}
							}
						}

						LazyColumn(
							modifier = Modifier
								.fillMaxSize()
								.focusRequester(listFocusRequester),
							contentPadding = PaddingValues(vertical = 8.dp),
						) {
							items(listItems, key = { it.key }) { listItem ->
								when (listItem) {
									is PickerListItem.SeasonHeader -> {
										SeasonHeaderRow(
											season = listItem.season,
											seasonNumber = listItem.seasonNumber,
											isExpanded = expandedSeasons[listItem.seasonNumber] == true,
											seasonEpisodes = seasonEpisodes[listItem.seasonNumber] ?: emptyList(),
											sonarrEpisodes = sonarrData.episodes.filter { it.seasonNumber == listItem.seasonNumber },
											vodEpisodes = vodData.episodes[listItem.seasonNumber.toString()] ?: emptyList(),
											selectedEpisodes = selectedEpisodes,
											today = today,
											onToggleExpand = {
												expandedSeasons[listItem.seasonNumber] =
													expandedSeasons[listItem.seasonNumber] != true
											},
											onSelectAll = { select ->
												val eps = seasonEpisodes[listItem.seasonNumber] ?: emptyList()
												val sonarrEps = sonarrData.episodes.filter { it.seasonNumber == listItem.seasonNumber }
												val vodEps = vodData.episodes[listItem.seasonNumber.toString()] ?: emptyList()
												for (ep in eps) {
													val key = "${listItem.seasonNumber}:${ep.episodeNumber}"
													val isVod = ep.episodeNumber in vodEps
													val isDl = sonarrEps.any { it.episodeNumber == ep.episodeNumber && it.hasFile }
													val isUnaired = isEpisodeUnaired(ep.airDate, today)
													if (!isVod && !isDl && !isUnaired) {
														selectedEpisodes[key] = select
													}
												}
											},
										)
									}
									is PickerListItem.EpisodeRow -> {
										val key = "${listItem.seasonNumber}:${listItem.episode.episodeNumber}"
										val sonarrEp = sonarrData.episodes.find {
											it.seasonNumber == listItem.seasonNumber && it.episodeNumber == listItem.episode.episodeNumber
										}
										val isVod = (vodData.episodes[listItem.seasonNumber.toString()] ?: emptyList())
											.contains(listItem.episode.episodeNumber)
										val isDl = sonarrEp?.hasFile == true
										val isUnaired = isEpisodeUnaired(listItem.episode.airDate, today)
										val isDisabled = isVod || isDl || isUnaired

										EpisodeItemRow(
											episode = listItem.episode,
											isSelected = selectedEpisodes[key] == true || isVod || isDl,
											isVod = isVod,
											isDownloaded = isDl,
											isUnaired = isUnaired,
											isDisabled = isDisabled,
											onToggle = {
												if (!isDisabled) {
													selectedEpisodes[key] = selectedEpisodes[key] != true
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

private sealed class PickerListItem(val key: String) {
	class SeasonHeader(val season: TmdbSeason, val seasonNumber: Int) : PickerListItem("season_$seasonNumber")
	class EpisodeRow(val episode: TmdbEpisode, val seasonNumber: Int) : PickerListItem("ep_${seasonNumber}_${episode.episodeNumber}")
}

@Composable
private fun SeasonHeaderRow(
	season: TmdbSeason,
	seasonNumber: Int,
	isExpanded: Boolean,
	seasonEpisodes: List<TmdbEpisode>,
	sonarrEpisodes: List<SonarrEpisode>,
	vodEpisodes: List<Int>,
	selectedEpisodes: Map<String, Boolean>,
	today: LocalDate,
	onToggleExpand: () -> Unit,
	onSelectAll: (Boolean) -> Unit,
) {
	var isFocused by remember { mutableStateOf(false) }

	// Count coverage
	val totalEps = seasonEpisodes.size
	val airedEps = seasonEpisodes.count { !isEpisodeUnaired(it.airDate, today) }
	val unairedCount = totalEps - airedEps
	val vodCount = vodEpisodes.size
	val dlCount = sonarrEpisodes.count { it.hasFile && it.seasonNumber == seasonNumber }
	val coveredCount = vodCount + dlCount
	val selectedInSeason = seasonEpisodes.count { ep ->
		selectedEpisodes["$seasonNumber:${ep.episodeNumber}"] == true
	}

	// Coverage color
	val coverageColor = when {
		coveredCount >= airedEps && airedEps > 0 -> Color(0xFF4CAF50)  // green - full
		coveredCount > 0 -> Color(0xFFFF9800)  // orange - partial
		else -> Color.White.copy(alpha = 0.5f)
	}

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.background(if (isFocused) Color(0xFF2d2d5e) else Color(0xFF1e1e3e))
			.then(
				if (isFocused) Modifier.border(2.dp, Color(0xFF7C6AE8), RoundedCornerShape(0.dp))
				else Modifier
			)
			.onFocusChanged { isFocused = it.isFocused }
			.focusable()
			.onKeyEvent { event ->
				if (event.type == KeyEventType.KeyUp) {
					when (event.key) {
						Key.Enter, Key.DirectionCenter -> { onToggleExpand(); true }
						else -> false
					}
				} else false
			}
			.padding(horizontal = 24.dp, vertical = 12.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Text(
				text = if (isExpanded) "▼" else "▶",
				fontSize = 12.sp,
				color = Color.White.copy(alpha = 0.6f),
			)
			Text(
				text = season.name.ifBlank { "Season $seasonNumber" },
				fontSize = 16.sp,
				fontWeight = FontWeight.Bold,
				color = Color.White,
			)

			// Coverage count
			Text(
				text = buildString {
					append("$coveredCount/$airedEps")
					if (unairedCount > 0) append(" +$unairedCount upcoming")
				},
				fontSize = 13.sp,
				color = coverageColor,
			)

			if (selectedInSeason > 0) {
				Text(
					text = "(+$selectedInSeason selected)",
					fontSize = 12.sp,
					color = Color(0xFF7C6AE8),
				)
			}
		}

		// Select all toggle for expanded season
		if (isExpanded) {
			val allSelectable = seasonEpisodes.count { ep ->
				val isVod = ep.episodeNumber in vodEpisodes
				val isDl = sonarrEpisodes.any { it.episodeNumber == ep.episodeNumber && it.hasFile }
				val isUnaired = isEpisodeUnaired(ep.airDate, today)
				!isVod && !isDl && !isUnaired
			}
			val allSelected = allSelectable > 0 && seasonEpisodes.all { ep ->
				val isVod = ep.episodeNumber in vodEpisodes
				val isDl = sonarrEpisodes.any { it.episodeNumber == ep.episodeNumber && it.hasFile }
				val isUnaired = isEpisodeUnaired(ep.airDate, today)
				isVod || isDl || isUnaired || selectedEpisodes["$seasonNumber:${ep.episodeNumber}"] == true
			}

			if (allSelectable > 0) {
				Button(
					onClick = { onSelectAll(!allSelected) },
					colors = ButtonDefaults.colors(
						containerColor = if (allSelected) Color(0xFF7C6AE8) else Color(0xFF374151),
						contentColor = Color.White,
					),
				) {
					Text(
						text = if (allSelected) "Deselect All" else "Select All",
						fontSize = 12.sp,
					)
				}
			}
		}
	}
}

@Composable
private fun EpisodeItemRow(
	episode: TmdbEpisode,
	isSelected: Boolean,
	isVod: Boolean,
	isDownloaded: Boolean,
	isUnaired: Boolean,
	isDisabled: Boolean,
	onToggle: () -> Unit,
) {
	var isFocused by remember { mutableStateOf(false) }
	val alpha = if (isUnaired) 0.5f else 1f

	Row(
		modifier = Modifier
			.fillMaxWidth()
			.background(
				when {
					isFocused -> Color(0xFF2a2a50)
					isSelected && !isDisabled -> Color(0xFF1a1a3e)
					else -> Color.Transparent
				}
			)
			.then(
				if (isFocused) Modifier.border(1.dp, Color(0xFF7C6AE8).copy(alpha = 0.5f), RoundedCornerShape(0.dp))
				else Modifier
			)
			.onFocusChanged { isFocused = it.isFocused }
			.focusable()
			.onKeyEvent { event ->
				if (event.type == KeyEventType.KeyUp &&
					(event.key == Key.Enter || event.key == Key.DirectionCenter)
				) {
					onToggle(); true
				} else false
			}
			.padding(horizontal = 40.dp, vertical = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(12.dp),
	) {
		// Checkbox
		Box(
			modifier = Modifier
				.width(20.dp)
				.height(20.dp)
				.border(
					width = 2.dp,
					color = when {
						isVod -> Color(0xFF4CAF50)
						isDownloaded -> Color(0xFF7C6AE8)
						isSelected -> Color(0xFF7C6AE8)
						else -> Color.White.copy(alpha = 0.3f * alpha)
					},
					shape = RoundedCornerShape(4.dp),
				)
				.background(
					color = when {
						isVod -> Color(0xFF4CAF50).copy(alpha = 0.3f)
						isDownloaded -> Color(0xFF7C6AE8).copy(alpha = 0.3f)
						isSelected -> Color(0xFF7C6AE8).copy(alpha = 0.3f)
						else -> Color.Transparent
					},
					shape = RoundedCornerShape(4.dp),
				),
			contentAlignment = Alignment.Center,
		) {
			if (isSelected || isVod || isDownloaded) {
				Text("✓", fontSize = 12.sp, color = Color.White.copy(alpha = alpha))
			}
		}

		// Episode number
		Text(
			text = "E${episode.episodeNumber}",
			fontSize = 14.sp,
			fontWeight = FontWeight.Bold,
			color = Color.White.copy(alpha = 0.7f * alpha),
			modifier = Modifier.width(36.dp),
		)

		// Episode name
		Text(
			text = episode.name,
			fontSize = 14.sp,
			color = Color.White.copy(alpha = 0.9f * alpha),
			modifier = Modifier.weight(1f),
		)

		// Badges
		if (isVod) {
			Badge(text = "VOD", color = Color(0xFF4CAF50))
		}
		if (isDownloaded) {
			Badge(text = "DL", color = Color(0xFF7C6AE8))
		}
		if (isUnaired) {
			val airText = episode.airDate?.let {
				try {
					val date = LocalDate.parse(it)
					date.format(DateTimeFormatter.ofPattern("MMM d"))
				} catch (_: Exception) { null }
			} ?: "TBA"
			Badge(text = airText, color = Color(0xFF616161))
		}
	}
}

@Composable
private fun Badge(text: String, color: Color) {
	Box(
		modifier = Modifier
			.background(color = color, shape = RoundedCornerShape(4.dp))
			.padding(horizontal = 8.dp, vertical = 2.dp),
	) {
		Text(text = text, fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
	}
}

private fun isEpisodeUnaired(airDate: String?, today: LocalDate): Boolean {
	if (airDate.isNullOrBlank()) return true
	return try {
		LocalDate.parse(airDate).isAfter(today)
	} catch (_: Exception) {
		false
	}
}
