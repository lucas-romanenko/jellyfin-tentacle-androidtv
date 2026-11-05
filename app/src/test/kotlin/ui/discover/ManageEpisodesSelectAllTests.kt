package org.jellyfin.androidtv.ui.discover

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.data.repository.SonarrEpisode
import org.jellyfin.androidtv.data.repository.TmdbEpisode
import java.time.LocalDate

/**
 * Manage Episodes sets what Sonarr monitors, so every episode can be ticked or unticked, whether
 * it streams, is downloaded or hasn't aired, as on the web UI. Add and Download More still lock
 * what is already there (#245).
 */
class ManageEpisodesSelectAllTests : FunSpec({
	val today = LocalDate.of(2026, 11, 5)
	// A season that streams in full (10 of 10 VOD), E3 also downloaded
	val streamed = (1..10).map { TmdbEpisode(episodeNumber = it, airDate = "2024-01-${10 + it}") }
	val vod = (1..10).toList()
	val sonarr = listOf(SonarrEpisode(seasonNumber = 5, episodeNumber = 3, monitored = true, hasFile = true))

	test("Manage: Select All on a fully streamed season sets every episode") {
		selectAllKeys(EpisodePickerMode.MANAGE, 5, streamed, sonarr, vod, today) shouldContainExactly
			(1..10).map { "5:$it" }
	}

	test("Manage: streamed, downloaded and upcoming rows can be ticked and show only what is picked") {
		isEpisodeLocked(EpisodePickerMode.MANAGE, isVod = true, isDl = false, isUnaired = false) shouldBe false
		isEpisodeLocked(EpisodePickerMode.MANAGE, isVod = false, isDl = true, isUnaired = false) shouldBe false
		isEpisodeLocked(EpisodePickerMode.MANAGE, isVod = false, isDl = false, isUnaired = true) shouldBe false
		isEpisodeTicked(EpisodePickerMode.MANAGE, selected = false, isVod = true, isDl = true) shouldBe false
		isEpisodeTicked(EpisodePickerMode.MANAGE, selected = true, isVod = true, isDl = false) shouldBe true
	}

	test("Manage: Select All includes episodes not aired yet") {
		val airing = listOf(
			TmdbEpisode(episodeNumber = 1, airDate = "2026-10-01"),
			TmdbEpisode(episodeNumber = 2, airDate = "2026-11-20"),
			TmdbEpisode(episodeNumber = 3, airDate = null),
		)
		selectAllKeys(EpisodePickerMode.MANAGE, 1, airing, emptyList(), listOf(1), today) shouldContainExactly
			listOf("1:1", "1:2", "1:3")
	}

	test("Download More: a fully streamed season has nothing to select, and owned rows stay locked and ticked") {
		selectAllKeys(EpisodePickerMode.DOWNLOAD_MORE, 5, streamed, sonarr, vod, today).shouldBeEmpty()
		selectAllKeys(EpisodePickerMode.ADD_NEW, 5, streamed, sonarr, vod, today).shouldBeEmpty()
		isEpisodeLocked(EpisodePickerMode.DOWNLOAD_MORE, isVod = true, isDl = false, isUnaired = false) shouldBe true
		isEpisodeLocked(EpisodePickerMode.ADD_NEW, isVod = false, isDl = false, isUnaired = true) shouldBe true
		isEpisodeTicked(EpisodePickerMode.DOWNLOAD_MORE, selected = false, isVod = false, isDl = true) shouldBe true
	}

	test("Download More: Select All sets only the aired episodes not there yet") {
		val partly = (1..10).map { TmdbEpisode(episodeNumber = it, airDate = "2024-01-${10 + it}") }
		selectAllKeys(EpisodePickerMode.DOWNLOAD_MORE, 1, partly, emptyList(), (1..9).toList(), today) shouldBe
			listOf("1:10")
	}
})
