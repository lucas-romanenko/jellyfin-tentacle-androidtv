package org.jellyfin.androidtv.ui.discover

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.data.repository.SonarrEpisode
import org.jellyfin.androidtv.data.repository.SonarrEpisodesResponse

/**
 * Manage Episodes' Confirm sends the whole monitored set; the server (discover.py
 * manage_episodes) unmonitors every episode of the series left out. So the starting selection
 * must hold what Sonarr monitors now, or Confirm unmonitors upcoming and still-missing episodes (#180).
 */
class ManageEpisodesSelectionTests : FunSpec({
	// An airing season: E1 downloaded, E2 aired and still wanted, E3 switched off earlier,
	// E4 and E5 upcoming; a monitored special.
	val airing = SonarrEpisodesResponse(
		inSonarr = true,
		episodes = listOf(
			SonarrEpisode(id = 1, seasonNumber = 1, episodeNumber = 1, monitored = true, hasFile = true),
			SonarrEpisode(id = 2, seasonNumber = 1, episodeNumber = 2, monitored = true),
			SonarrEpisode(id = 3, seasonNumber = 1, episodeNumber = 3, monitored = false),
			SonarrEpisode(id = 4, seasonNumber = 1, episodeNumber = 4, monitored = true),
			SonarrEpisode(id = 5, seasonNumber = 1, episodeNumber = 5, monitored = true),
			SonarrEpisode(id = 6, seasonNumber = 0, episodeNumber = 1, monitored = true),
		),
	)

	/** What the server would unmonitor for a Confirm with [selected] (discover.py's rule). */
	fun unmonitoredBy(selected: List<String>) = airing.episodes
		.filter { it.monitored && "${it.seasonNumber}:${it.episodeNumber}" !in selected }
		.map { "${it.seasonNumber}:${it.episodeNumber}" }

	test("Confirm without a change unmonitors nothing") {
		val start = manageStartSelection(airing)
		start shouldContainExactlyInAnyOrder listOf("1:1", "1:2", "1:4", "1:5", "0:1")
		unmonitoredBy(start).shouldBeEmpty()
	}

	test("ticking a switched-off episode keeps the upcoming ones monitored") {
		val selected = manageStartSelection(airing) + "1:3"
		unmonitoredBy(selected).shouldBeEmpty()
	}

	test("unticking an aired episode unmonitors only that one") {
		val selected = manageStartSelection(airing) - "1:2"
		unmonitoredBy(selected) shouldBe listOf("1:2")
	}

	test("a downloaded episode Sonarr no longer monitors is still ticked") {
		val sonarr = SonarrEpisodesResponse(
			inSonarr = true,
			episodes = listOf(SonarrEpisode(seasonNumber = 2, episodeNumber = 7, monitored = false, hasFile = true)),
		)
		manageStartSelection(sonarr) shouldBe listOf("2:7")
	}

	test("Sonarr's list failed to load: nothing ticked and Confirm stays off") {
		val failed = SonarrEpisodesResponse()
		manageStartSelection(failed).shouldBeEmpty()
		canConfirmEpisodes(EpisodePickerMode.MANAGE, failed, selectedCount = 1) shouldBe false
		canConfirmEpisodes(EpisodePickerMode.MANAGE, airing, selectedCount = 1) shouldBe true
		canConfirmEpisodes(EpisodePickerMode.MANAGE, airing, selectedCount = 0) shouldBe false
	}

	test("adding new episodes doesn't need Sonarr's list") {
		canConfirmEpisodes(EpisodePickerMode.ADD_NEW, SonarrEpisodesResponse(), selectedCount = 2) shouldBe true
		canConfirmEpisodes(EpisodePickerMode.DOWNLOAD_MORE, SonarrEpisodesResponse(), selectedCount = 0) shouldBe false
	}
})
