package org.jellyfin.androidtv.ui.discover

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.jellyfin.androidtv.data.repository.TmdbEpisode
import org.jellyfin.androidtv.data.repository.TmdbSeason
import org.jellyfin.androidtv.ui.base.Text
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/**
 * Screenshots of the Manage Episodes rows (#889) on a TV emulator, for reviewing the badges:
 * the TV screenshots workflow pulls them from the app's files/screens. Not a check of its own.
 */
@RunWith(AndroidJUnit4::class)
class ManageEpisodesScreenshotTest {
	@get:Rule
	val compose = createComposeRule()

	private class Fake(val number: Int, val name: String, val source: String, val airDate: String) {
		val inVod = source == "both" || source == "stream"
		val hasFile = source == "both" || source == "download"
	}

	private val today = LocalDate.now()
	private val fakes = listOf(
		Fake(1, "Pilot", "both", "2024-01-07"),
		Fake(2, "The Second One", "download", "2024-01-14"),
		Fake(3, "Streams Only", "stream", "2024-01-21"),
		Fake(4, "Nobody Has This", "missing", "2024-01-28"),
		Fake(5, "Monitored, Still Missing", "missing", "2024-02-04"),
		Fake(6, "Next Week", "upcoming", today.plusDays(7).toString()),
	)

	/** The server sends where each episode plays from. */
	@Test
	fun withSource() = shoot("tv-manage-episodes-source", useSource = true)

	/** An older server: the VOD list, hasFile and the air date. */
	@Test
	fun withoutSource() = shoot("tv-manage-episodes-fallback", useSource = false)

	private fun shoot(name: String, useSource: Boolean) {
		val episodes = fakes.map { TmdbEpisode(episodeNumber = it.number, name = it.name, airDate = it.airDate) }
		val badges = fakes.associate { fake ->
			fake.number to episodeBadges(
				mode = EpisodePickerMode.MANAGE,
				source = if (useSource) fake.source else null,
				inVodList = fake.inVod,
				hasFile = fake.hasFile,
				unaired = LocalDate.parse(fake.airDate).isAfter(today),
			)
		}
		// Manage Episodes starts with what Sonarr monitors ticked
		val selected = mapOf("1:5" to true)

		compose.setContent {
			// The dialog as Discover shows it: 75% x 85% of the screen over the dimmed page
			Box(
				modifier = Modifier.fillMaxSize().background(Color(0xFF101018)),
				contentAlignment = Alignment.Center,
			) {
				Box(
					modifier = Modifier
						.fillMaxWidth(0.75f)
						.fillMaxHeight(0.85f)
						.clip(RoundedCornerShape(16.dp))
						.background(Color(0xFF1a1a2e)),
				) {
					Column {
						Column(
							modifier = Modifier
								.fillMaxWidth()
								.background(Color(0xFF252547))
								.padding(horizontal = 24.dp, vertical = 16.dp),
						) {
							Text("Manage Episodes", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
							Text("Example Show", fontSize = 14.sp, color = Color.White.copy(alpha = 0.6f))
						}
						SeasonHeaderRow(
							season = TmdbSeason(seasonNumber = 1, name = "Season 1"),
							seasonNumber = 1,
							isExpanded = true,
							seasonEpisodes = episodes,
							badgesFor = { badges.getValue(it.episodeNumber) },
							selectedEpisodes = selected,
							onToggleExpand = {},
							onSelectAll = {},
						)
						for (episode in episodes) {
							val b = badges.getValue(episode.episodeNumber)
							EpisodeItemRow(
								episode = episode,
								isSelected = selected["1:${episode.episodeNumber}"] == true || b.vod || b.downloaded,
								isVod = b.vod,
								isDownloaded = b.downloaded,
								isUnaired = b.unaired,
								isMissing = b.missing,
								isDisabled = !b.selectable,
								onToggle = {},
							)
						}
					}
				}
			}
		}
		compose.waitForIdle()

		val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
		val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "screens").apply { mkdirs() }
		File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
	}
}
