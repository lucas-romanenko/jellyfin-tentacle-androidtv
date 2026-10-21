package org.jellyfin.androidtv.ui.search

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import org.jellyfin.androidtv.data.repository.DiscoverItem
import org.jellyfin.androidtv.ui.search.composable.searchCardSubtitle
import org.jellyfin.androidtv.ui.search.composable.searchCardTitle
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/** Search finds Live TV channels and library items through Jellyfin, next to the Discover results. */
class LibrarySearchTests : FunSpec({
	fun channel(name: String, number: String? = null) =
		BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.TV_CHANNEL, name = name, number = number)

	fun item(type: BaseItemKind, name: String, tmdb: String? = null) =
		BaseItemDto(id = UUID.randomUUID(), type = type, name = name, providerIds = tmdb?.let { mapOf("Tmdb" to it) })

	test("channels: Jellyfin is asked for TV channels only, with room to rank") {
		val repo = mockk<SearchRepository>()
		coEvery { repo.search(any(), any(), any()) } returns Result.success(listOf(channel("TSN 1")))

		LibrarySearch(repo).channels("tsn").map { it.name } shouldBe listOf("TSN 1")

		coVerify(exactly = 1) { repo.search("tsn", setOf(BaseItemKind.TV_CHANNEL), LibrarySearch.CHANNEL_FETCH_LIMIT) }
	}

	test("channels: names that start with the query come first, in-word matches last, A-Z within") {
		// Jellyfin matches inside words ("TSN" also finds "SPORTSNET") and lists "TSN 5" before "TSN 1".
		val found = listOf(channel("SPORTSNET ONE"), channel("CA| TSN 5"), channel("SPORTSNET 360"), channel("TSN 1"), channel("ca| tsn 1"))
		rankChannelMatches("tsn", found).map { it.name } shouldBe
			listOf("TSN 1", "ca| tsn 1", "CA| TSN 5", "SPORTSNET 360", "SPORTSNET ONE")
	}

	test("channels: a later word-start match counts even after an in-word one") {
		rankChannelMatches("news", listOf(channel("Sky Sports"), channel("FOXNEWS / NEWSMAX"))).map { it.name } shouldBe
			listOf("FOXNEWS / NEWSMAX", "Sky Sports")
		rankChannelMatches("NEWS", listOf(channel("FOXNEWS"), channel("cbc news"))).map { it.name } shouldBe
			listOf("cbc news", "FOXNEWS")
	}

	test("channels: the row is capped after ranking") {
		val repo = mockk<SearchRepository>()
		val inWord = List(60) { channel("SPORTSNET $it") }
		val wordStart = List(5) { channel("TSN $it") }
		coEvery { repo.search(any(), any(), any()) } returns Result.success(inWord + wordStart)

		val row = LibrarySearch(repo).channels("tsn")
		row.size shouldBe LibrarySearch.CHANNEL_ROW_LIMIT
		row.take(5).map { it.name } shouldBe wordStart.map { it.name }
	}

	test("library: movies, series, episodes and videos (recordings, YouTube library)") {
		val repo = mockk<SearchRepository>()
		coEvery { repo.search(any(), any(), any()) } returns Result.success(listOf(item(BaseItemKind.VIDEO, "QA Tube A")))

		LibrarySearch(repo).library("qa tube").map { it.name } shouldBe listOf("QA Tube A")

		coVerify(exactly = 1) {
			repo.search(
				"qa tube",
				setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE, BaseItemKind.VIDEO),
				25,
			)
		}
	}

	test("a failed Jellyfin search gives an empty row, not an error") {
		val failing = mockk<SearchRepository>()
		coEvery { failing.search(any(), any(), any()) } returns Result.failure(IllegalStateException("401"))
		LibrarySearch(failing).channels("tsn").shouldBeEmpty()
		LibrarySearch(failing).library("tsn").shouldBeEmpty()

		val throwing = mockk<SearchRepository>()
		coEvery { throwing.search(any(), any(), any()) } throws IllegalStateException("offline")
		LibrarySearch(throwing).channels("tsn").shouldBeEmpty()
		LibrarySearch(throwing).library("tsn").shouldBeEmpty()
	}

	test("a newer keystroke's cancellation is passed on") {
		val repo = mockk<SearchRepository>()
		coEvery { repo.search(any(), any(), any()) } throws CancellationException("newer query")
		shouldThrow<CancellationException> { LibrarySearch(repo).channels("tsn") }
	}

	test("items parental controls hide are left out") {
		val repo = mockk<SearchRepository>()
		val kept = item(BaseItemKind.MOVIE, "Kept")
		val hidden = item(BaseItemKind.MOVIE, "Hidden")
		coEvery { repo.search(any(), any(), any()) } returns Result.success(listOf(kept, hidden))

		LibrarySearch(repo) { it.name == "Hidden" }.library("x") shouldBe listOf(kept)
		LibrarySearch(repo) { it.name == "Hidden" }.channels("x") shouldBe listOf(kept)
	}

	test("owned titles the Discover row already shows aren't listed twice") {
		val movie = item(BaseItemKind.MOVIE, "Lantern Field", tmdb = "11")
		val series = item(BaseItemKind.SERIES, "Silent Coast", tmdb = "22")
		val recording = item(BaseItemKind.EPISODE, "World Report")
		val otherMovie = item(BaseItemKind.MOVIE, "Paper Lanterns", tmdb = "33")
		val sameIdOtherKind = item(BaseItemKind.SERIES, "Lantern Field (TV)", tmdb = "11")
		val discover = listOf(
			DiscoverItem(tmdbId = 11, title = "Lantern Field", mediaType = "movie", inLibrary = true),
			DiscoverItem(tmdbId = 22, title = "Silent Coast", mediaType = "series", inLibrary = true),
			DiscoverItem(tmdbId = 33, title = "Paper Lanterns", mediaType = "movie", inLibrary = false),
		)

		libraryRowItems(listOf(movie, series, recording, otherMovie, sameIdOtherKind), discover) shouldBe
			listOf(recording, otherMovie, sameIdOtherKind)
		libraryRowItems(listOf(movie, recording), emptyList()) shouldBe listOf(movie, recording)
	}

	test("card text: channel number, episode under its series") {
		searchCardSubtitle(channel("TSN 1", number = "216")) shouldBe "216"
		val episode = BaseItemDto(
			id = UUID.randomUUID(), type = BaseItemKind.EPISODE, name = "Pilot", seriesName = "Silent Coast",
			parentIndexNumber = 1, indexNumber = 2,
		)
		searchCardTitle(episode) shouldBe "Silent Coast"
		searchCardSubtitle(episode) shouldBe "S1E2 · Pilot"
		searchCardTitle(item(BaseItemKind.VIDEO, "QA Tube A")) shouldBe "QA Tube A"
	}
})
