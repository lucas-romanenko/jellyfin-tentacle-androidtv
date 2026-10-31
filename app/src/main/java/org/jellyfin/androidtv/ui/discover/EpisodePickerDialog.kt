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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.data.repository.AddResult
import org.jellyfin.androidtv.data.repository.SENT_FOR_APPROVAL
import org.jellyfin.androidtv.data.repository.SelectedEpisode
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
	tvdbId: Int = 0,
	tentacleRepository: TentacleRepository,
	onDismiss: () -> Unit,
	onComplete: (message: String) -> Unit,
	/** The add was sent to the admin for approval (a family member's request). */
	onRequested: () -> Unit = {},
) {
	var isLoading by remember { mutableStateOf(true) }
	var isSubmitting by remember { mutableStateOf(false) }
	var submitSuccess by remember { mutableStateOf(false) }
	var submitMessage by remember { mutableStateOf("") }
	var submitOk by remember { mutableStateOf(true) }
	var seasons by remember { mutableStateOf<List<TmdbSeason>>(emptyList()) }
	var seasonEpisodes by remember { mutableStateOf<Map<Int, List<TmdbEpisode>>>(emptyMap()) }
	var sonarrData by remember { mutableStateOf(SonarrEpisodesResponse()) }
	var vodData by remember { mutableStateOf(VodEpisodesResponse()) }
	val expandedSeasons = remember { mutableStateMapOf<Int, Boolean>() }
	val selectedEpisodes = remember { mutableStateMapOf<String, Boolean>() } // "s:e" -> selected
	val scope = rememberCoroutineScope()
	// The submit must outlive this composable: closing the dialog sets the parent's
	// showEpisodePicker=false, which removes this content and cancels rememberCoroutineScope().
	// Use the hosting fragment's lifecycle scope so an in-flight Sonarr write isn't cancelled.
	val submitScope = LocalLifecycleOwner.current.lifecycleScope
	val listFocusRequester = remember { FocusRequester() }

	// Load all data in parallel
	LaunchedEffect(tmdbId) {
		val seasonsResult = tentacleRepository.getSeasons(tmdbId, tvdbId)
		seasons = seasonsResult?.seasons?.filter { (it.seasonNumber ?: 0) > 0 } ?: emptyList()

		if (seasons.isNotEmpty()) {
			val episodeJobs = seasons.map { season ->
				scope.async {
					val eps = tentacleRepository.getSeasonEpisodes(tmdbId, season.seasonNumber ?: 0, tvdbId)
					(season.seasonNumber ?: 0) to eps
				}
			}
			val sonarrJob = scope.async { tentacleRepository.getSonarrEpisodes(tmdbId) }
			val vodJob = scope.async { tentacleRepository.getVodEpisodes(tmdbId) }

			val episodeResults = episodeJobs.awaitAll()
			seasonEpisodes = episodeResults.toMap()
			sonarrData = sonarrJob.await()
			vodData = vodJob.await()

			if (mode == EpisodePickerMode.MANAGE) {
				manageStartSelection(sonarrData).forEach { selectedEpisodes[it] = true }
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

	// What each episode row shows (and whether it can be ticked), from Sonarr's and the VOD lists
	fun badgesFor(seasonNumber: Int, episode: TmdbEpisode): EpisodeBadges {
		val sonarrEp = sonarrData.episodes.find {
			it.seasonNumber == seasonNumber && it.episodeNumber == episode.episodeNumber
		}
		return episodeBadges(
			mode = mode,
			source = sonarrEp?.source,
			inVodList = episode.episodeNumber in (vodData.episodes[seasonNumber.toString()] ?: emptyList()),
			hasFile = sonarrEp?.hasFile == true,
			unaired = isEpisodeUnaired(episode.airDate, today),
		)
	}

	// Show success overlay instead of dialog content after submit
	if (submitSuccess) {
		Box(
			modifier = Modifier.fillMaxSize(),
			contentAlignment = Alignment.Center,
		) {
			Column(
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = Arrangement.spacedBy(16.dp),
			) {
				Box(
					modifier = Modifier
						.size(72.dp)
						.background(if (submitOk) Color(0xFF4CAF50) else Color(0xFFE53935), androidx.compose.foundation.shape.CircleShape),
					contentAlignment = Alignment.Center,
				) {
					Text(if (submitOk) "✓" else "✕", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White)
				}
				Text(
					text = submitMessage,
					fontSize = 16.sp,
					color = Color.White,
				)
			}
		}
		return
	}

	val selectedCount = selectedEpisodes.count { it.value }
	val canConfirm = canConfirmEpisodes(mode, sonarrData, selectedCount)

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
						if (!isSubmitting && canConfirm) {
							isSubmitting = true
							submitScope.launch {
								val selected = selectedEpisodes
									.filter { it.value }
									.map { entry ->
										val parts = entry.key.split(":")
										SelectedEpisode(parts[0].toInt(), parts[1].toInt())
									}

								val outcome = when (mode) {
									EpisodePickerMode.ADD_NEW, EpisodePickerMode.DOWNLOAD_MORE -> {
										val result = tentacleRepository.addToSonarrWithEpisodes(
											tmdbId = tmdbId,
											qualityProfileId = qualityProfileId,
											monitor = "none",
											selectedEpisodes = selected,
											autoFollow = autoFollow,
											tvdbId = tvdbId,
										)
										val outcome = addEpisodesOutcome(result, selectedCount)
										// A request waiting for approval downloads nothing yet
										if (outcome.first && result.pending > 0) onRequested()
										else if (outcome.first) tentacleRepository.bumpActivityDownloadCount(selectedCount)
										outcome
									}
									EpisodePickerMode.MANAGE -> {
										val result = tentacleRepository.manageEpisodes(tmdbId, selected)
										if (result.success) {
											true to ("Monitoring ${result.monitored} episodes" +
												if (result.searching > 0) ", searching ${result.searching}" else "")
										} else false to ("Failed to update" + (result.error?.let { ": $it" } ?: ""))
									}
								}
								val (ok, message) = outcome
								submitMessage = message
								submitOk = ok
								submitSuccess = true
								delay(if (ok) 1500 else 3500)
								onComplete(message)
							}
						}
					},
					colors = ButtonDefaults.colors(
						containerColor = if (canConfirm) Color(0xFF7C6AE8) else Color(0xFF374151),
						contentColor = Color.White,
					),
				) {
					Text(
						text = when {
							isSubmitting -> "Saving..."
							canConfirm -> "Confirm ($selectedCount)"
							mode == EpisodePickerMode.MANAGE && !isLoading && !sonarrData.inSonarr -> "Couldn't load Sonarr"
							else -> "Select episodes"
						},
						fontSize = 14.sp,
					)
				}
			}
		}

		if (isLoading) {
			Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				Text("Loading episodes...", fontSize = 16.sp, color = Color.White.copy(alpha = 0.7f))
			}
		} else if (seasons.isEmpty()) {
			Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
				Text("No seasons found", fontSize = 16.sp, color = Color.White.copy(alpha = 0.5f))
			}
		} else {
			val listItems = remember(seasons, seasonEpisodes, expandedSeasons.toMap()) {
				buildList {
					for (season in seasons) {
						val sNum = season.seasonNumber ?: continue
						add(PickerListItem.SeasonHeader(season, sNum))
						if (expandedSeasons[sNum] == true) {
							val eps = seasonEpisodes[sNum] ?: emptyList()
							if (eps.isEmpty()) {
								// TMDB knows the season exists but has no episode data yet
								add(PickerListItem.UnairedPlaceholder(sNum))
							} else {
								eps.forEach { ep -> add(PickerListItem.EpisodeRow(ep, sNum)) }
							}
						}
					}
				}
			}
			LazyColumn(
				modifier = Modifier.fillMaxSize().focusRequester(listFocusRequester),
				contentPadding = PaddingValues(vertical = 8.dp),
			) {
				// Index-prefixed: TMDB occasionally lists the same episode number
				// twice in a season, and a duplicate key crashes the app.
				itemsIndexed(listItems, key = { index, it -> "$index:${it.key}" }) { _, listItem ->
					when (listItem) {
						is PickerListItem.SeasonHeader -> {
							SeasonHeaderRow(
								season = listItem.season,
								seasonNumber = listItem.seasonNumber,
								isExpanded = expandedSeasons[listItem.seasonNumber] == true,
								seasonEpisodes = seasonEpisodes[listItem.seasonNumber] ?: emptyList(),
								badgesFor = { badgesFor(listItem.seasonNumber, it) },
								selectedEpisodes = selectedEpisodes,
								onToggleExpand = {
									expandedSeasons[listItem.seasonNumber] = expandedSeasons[listItem.seasonNumber] != true
								},
								onSelectAll = { select ->
									val eps = seasonEpisodes[listItem.seasonNumber] ?: emptyList()
									for (ep in eps) {
										val key = "${listItem.seasonNumber}:${ep.episodeNumber}"
										if (badgesFor(listItem.seasonNumber, ep).selectable) selectedEpisodes[key] = select
									}
								},
							)
						}
						is PickerListItem.UnairedPlaceholder -> {
							UnairedSeasonRow()
						}
						is PickerListItem.EpisodeRow -> {
							val key = "${listItem.seasonNumber}:${listItem.episode.episodeNumber}"
							val badges = badgesFor(listItem.seasonNumber, listItem.episode)
							val isDisabled = !badges.selectable
							EpisodeItemRow(
								episode = listItem.episode,
								isSelected = selectedEpisodes[key] == true || badges.vod || badges.downloaded,
								isVod = badges.vod,
								isDownloaded = badges.downloaded,
								isUnaired = badges.unaired,
								isMissing = badges.missing,
								isDisabled = isDisabled,
								onToggle = { if (!isDisabled) selectedEpisodes[key] = selectedEpisodes[key] != true },
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
	class UnairedPlaceholder(val seasonNumber: Int) : PickerListItem("unaired_$seasonNumber")
}

@Composable
internal fun SeasonHeaderRow(
	season: TmdbSeason,
	seasonNumber: Int,
	isExpanded: Boolean,
	seasonEpisodes: List<TmdbEpisode>,
	badgesFor: (TmdbEpisode) -> EpisodeBadges,
	selectedEpisodes: Map<String, Boolean>,
	onToggleExpand: () -> Unit,
	onSelectAll: (Boolean) -> Unit,
) {
	var isFocused by remember { mutableStateOf(false) }

	// Count coverage (an episode both downloaded and streamed counts once)
	val badges = seasonEpisodes.map(badgesFor)
	val totalEps = seasonEpisodes.size
	val airedEps = badges.count { !it.unaired }
	val unairedCount = totalEps - airedEps
	val coveredCount = badges.count { it.vod || it.downloaded }
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

			// Coverage count ("Not aired yet" for seasons with no aired episodes)
			if (airedEps == 0) {
				Text(
					text = "Not aired yet",
					fontSize = 13.sp,
					color = Color.White.copy(alpha = 0.4f),
				)
			} else {
				Text(
					text = buildString {
						append("$coveredCount/$airedEps")
						if (unairedCount > 0) append(" +$unairedCount upcoming")
					},
					fontSize = 13.sp,
					color = coverageColor,
				)
			}


		}

		// Select all toggle for expanded season
		if (isExpanded) {
			val allSelectable = badges.count { it.selectable }
			val allSelected = allSelectable > 0 && seasonEpisodes.indices.all { i ->
				!badges[i].selectable || selectedEpisodes["$seasonNumber:${seasonEpisodes[i].episodeNumber}"] == true
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
private fun UnairedSeasonRow() {
	// Non-focusable placeholder shown when an expanded season has no episode data yet
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 40.dp, vertical = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = "This season hasn't aired yet",
			fontSize = 14.sp,
			color = Color.White.copy(alpha = 0.4f),
		)
	}
}

@Composable
internal fun EpisodeItemRow(
	episode: TmdbEpisode,
	isSelected: Boolean,
	isVod: Boolean,
	isDownloaded: Boolean,
	isUnaired: Boolean,
	isDisabled: Boolean,
	/** Aired, but neither downloaded nor a stream (Manage Episodes only). */
	isMissing: Boolean = false,
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
		if (isMissing) {
			Badge(text = "Missing", color = Color(0xFF616161))
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

/** What an episode row shows: its VOD, DL, air date and Missing badges. */
internal data class EpisodeBadges(
	val vod: Boolean,
	val downloaded: Boolean,
	val unaired: Boolean,
	val missing: Boolean = false,
) {
	/** Only an aired episode with no copy can be ticked (Manage Episodes keeps the rest monitored). */
	val selectable: Boolean get() = !vod && !downloaded && !unaired
}

/**
 * Where an episode plays from (#889). Manage Episodes takes the server's `source` when it sends
 * one; older servers, and the other modes, go by the VOD list, Sonarr's hasFile and the air date.
 * Missing (aired, no copy) shows in Manage Episodes only: elsewhere an unticked row already means
 * "can be downloaded".
 */
internal fun episodeBadges(
	mode: EpisodePickerMode,
	source: String?,
	inVodList: Boolean,
	hasFile: Boolean,
	unaired: Boolean,
): EpisodeBadges {
	if (mode == EpisodePickerMode.MANAGE) when (source) {
		"both" -> return EpisodeBadges(vod = true, downloaded = true, unaired = false)
		"download" -> return EpisodeBadges(vod = false, downloaded = true, unaired = false)
		"stream" -> return EpisodeBadges(vod = true, downloaded = false, unaired = false)
		"upcoming" -> return EpisodeBadges(vod = false, downloaded = false, unaired = true)
		"missing" -> return EpisodeBadges(vod = false, downloaded = false, unaired = false, missing = true)
	}
	val missing = mode == EpisodePickerMode.MANAGE && !inVodList && !hasFile && !unaired
	return EpisodeBadges(vod = inVodList, downloaded = hasFile, unaired = unaired, missing = missing)
}

/**
 * Manage Episodes starts from what Sonarr monitors now, plus what is on disk (#180). Confirm
 * sends the whole monitored set and the server unmonitors every episode of the series left out,
 * so an upcoming or still-missing episode not ticked here would be unmonitored untouched.
 */
internal fun manageStartSelection(sonarr: SonarrEpisodesResponse): List<String> =
	if (!sonarr.inSonarr) emptyList()
	else sonarr.episodes.filter { it.monitored || it.hasFile }.map { "${it.seasonNumber}:${it.episodeNumber}" }

/**
 * Confirm needs a ticked episode, and in Manage Episodes Sonarr's list too: without it (the load
 * failed) the ticked ones would replace the whole monitored set and unmonitor the rest (#180).
 */
internal fun canConfirmEpisodes(mode: EpisodePickerMode, sonarr: SonarrEpisodesResponse, selectedCount: Int): Boolean =
	selectedCount > 0 && (mode != EpisodePickerMode.MANAGE || sonarr.inSonarr)

/**
 * What adding picked episodes to Sonarr came to: (done, message). A 200 can still carry
 * added=0 with failed or already_exists, so the counts decide, as the Discover add button's do.
 * A series already in Sonarr comes back already_exists WITHOUT the picked episodes applied
 * (the server's add returns early), so that is not done: Manage Episodes applies them (#21).
 * A family member's request that waits for the admin's approval (pending) is done: the
 * server's `detail` says who approves it.
 */
internal fun addEpisodesOutcome(result: AddResult, selectedCount: Int): Pair<Boolean, String> = when {
	result.error != null -> false to "Error: ${result.error}"
	result.added > 0 -> true to "Added $selectedCount episodes to Sonarr"
	result.alreadyExists > 0 -> false to "Already in Sonarr, so the picked episodes were not added. Use Manage Episodes."
	result.pending > 0 -> true to (result.detail?.takeIf { it.isNotBlank() } ?: SENT_FOR_APPROVAL)
	else -> false to (result.detail ?: "Failed to add to Sonarr")
}
