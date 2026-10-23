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

	fun item(type: BaseItemKind, name: String, tmdb: String? = null, path: String? = null) =
		BaseItemDto(id = UUID.randomUUID(), type = type, name = name, providerIds = tmdb?.let { mapOf("Tmdb" to it) }, path = path)

	fun episode(name: String, series: BaseItemDto) =
		BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.EPISODE, name = name, seriesId = series.id, seriesName = series.name)

	/** Jellyfin with these titles (movies, shows, videos) and episodes; shows are looked up by id. */
	fun jellyfin(titles: List<BaseItemDto>, episodes: List<BaseItemDto> = emptyList(), shows: List<BaseItemDto> = emptyList()) =
		mockk<SearchRepository>().also { repo ->
			coEvery { repo.search(any(), LibrarySearch.libraryKinds, any()) } returns Result.success(titles)
			coEvery { repo.search(any(), LibrarySearch.episodeKinds, any()) } returns Result.success(episodes)
			coEvery { repo.items(any()) } answers { Result.success(shows.filter { it.id in firstArg<Collection<UUID>>() }) }
		}

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

	test("library: movies, series and videos (YouTube library, recordings), episodes asked for separately") {
		val repo = jellyfin(listOf(item(BaseItemKind.MOVIE, "Maple Leafs Post Game", path = "/data/media/youtube/Toronto Maple Leafs/a.mp4")))

		LibrarySearch(repo).library("maple leafs").map { it.name } shouldBe listOf("Maple Leafs Post Game")

		coVerify(exactly = 1) {
			repo.search("maple leafs", setOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.VIDEO), 25)
			repo.search("maple leafs", setOf(BaseItemKind.EPISODE), 25)
		}
		coVerify(exactly = 0) { repo.items(any()) }
	}

	test("library: no episodes; a show Discover can't list is shown once for its matching episodes") {
		// "office": Jellyfin finds episodes named "Office …" across many shows. Shows TMDB knows
		// are left to Discover (searching the show finds it, badged In Library), so they're dropped.
		val jackass = item(BaseItemKind.SERIES, "Jackass", tmdb = "1795")
		val homeShow = item(BaseItemKind.SERIES, "Office Tapes", path = "/data/media/tv/Office Tapes")
		val recorded = item(BaseItemKind.SERIES, "World Report", tmdb = "77", path = "/config/data/livetv/recordings/World Report")
		val youtube = item(BaseItemKind.MOVIE, "Office tour", path = "/data/media/youtube/Some Channel/b.mp4")
		val video = item(BaseItemKind.VIDEO, "Office party 2019")
		val repo = jellyfin(
			titles = listOf(youtube, video),
			episodes = listOf(
				episode("Office Chairs", jackass),
				episode("Office Day 1", homeShow),
				episode("Office Day 2", homeShow),
				episode("Office Move", recorded),
			),
			shows = listOf(jackass, homeShow, recorded),
		)

		val row = LibrarySearch(repo).library("office")
		row.map { it.name } shouldBe listOf("Office tour", "Office party 2019", "Office Tapes", "World Report")
		row.none { it.type == BaseItemKind.EPISODE } shouldBe true
		coVerify(exactly = 1) { repo.items(setOf(jackass.id, homeShow.id, recorded.id).toList()) }
	}

	test("library: a show found by name isn't added again for its episodes") {
		val show = item(BaseItemKind.SERIES, "Office Tapes")
		val repo = jellyfin(titles = listOf(show), episodes = listOf(episode("Office Tapes 1", show)), shows = listOf(show))

		LibrarySearch(repo).library("office tapes") shouldBe listOf(show)
		coVerify(exactly = 0) { repo.items(any()) }
	}

	test("library: a failed show lookup still gives the other matches") {
		val video = item(BaseItemKind.VIDEO, "Office party")
		val repo = jellyfin(titles = listOf(video), episodes = listOf(episode("Office", item(BaseItemKind.SERIES, "X"))))
		coEvery { repo.items(any()) } returns Result.failure(IllegalStateException("500"))

		LibrarySearch(repo).library("office") shouldBe listOf(video)
	}

	test("a failed Jellyfin search gives an empty row, not an error") {
		val failing = mockk<SearchRepository>()
		coEvery { failing.search(any(), any(), any()) } returns Result.failure(IllegalStateException("401"))
		LibrarySearch(failing).channels("tsn").shouldBeEmpty()
		LibrarySearch(failing).library("tsn").shouldBeEmpty()

		val throwing = mockk<SearchRepository>()
		coEvery { throwing.search(any(), any(), any()) } throws IllegalStateException("offline")
		coEvery { throwing.items(any()) } throws IllegalStateException("offline")
		LibrarySearch(throwing).channels("tsn").shouldBeEmpty()
		LibrarySearch(throwing).library("tsn").shouldBeEmpty()
	}

	test("a newer keystroke's cancellation is passed on") {
		val repo = mockk<SearchRepository>()
		coEvery { repo.search(any(), any(), any()) } throws CancellationException("newer query")
		shouldThrow<CancellationException> { LibrarySearch(repo).channels("tsn") }
	}

	test("items parental controls hide are left out") {
		val kept = item(BaseItemKind.MOVIE, "Kept")
		val hidden = item(BaseItemKind.MOVIE, "Hidden")
		val hiddenShow = item(BaseItemKind.SERIES, "Hidden Show")
		val repo = jellyfin(listOf(kept, hidden), listOf(episode("Pilot", hiddenShow)), listOf(hiddenShow))
		coEvery { repo.search(any(), LibrarySearch.channelKinds, any()) } returns Result.success(listOf(kept, hidden))

		LibrarySearch(repo) { it.name.orEmpty().startsWith("Hidden") }.library("x") shouldBe listOf(kept)
		LibrarySearch(repo) { it.name == "Hidden" }.channels("x") shouldBe listOf(kept)
	}

	test("owned titles the Discover row already shows aren't listed twice") {
		val movie = item(BaseItemKind.MOVIE, "Lantern Field", tmdb = "11")
		val series = item(BaseItemKind.SERIES, "Silent Coast", tmdb = "22")
		val recording = item(BaseItemKind.VIDEO, "World Report")
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
