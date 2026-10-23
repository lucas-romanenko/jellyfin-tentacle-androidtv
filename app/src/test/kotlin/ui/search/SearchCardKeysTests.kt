package org.jellyfin.androidtv.ui.search

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.data.repository.DiscoverItem
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/** Back from a channel opened in Search left no card focused; the opened card is found again by its key. */
class SearchCardKeysTests : FunSpec({
	val tsn = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.TV_CHANNEL, name = "TSN 1")
	val recording = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.VIDEO, name = "TSN Classic")
	val movie = DiscoverItem(tmdbId = 42, title = "TSN: The Movie", mediaType = "movie")

	test("a card shown again has the same key") {
		val shown = shownSearchCards(listOf(tsn), listOf(recording), listOf(movie))
		(searchCardKey(SearchCardRow.CHANNELS, tsn) in shown) shouldBe true
		(searchCardKey(SearchCardRow.LIBRARY, recording) in shown) shouldBe true
		(discoverCardKey(movie.copy()) in shown) shouldBe true
	}

	test("the row is part of the key") {
		val shown = shownSearchCards(listOf(tsn), emptyList(), emptyList())
		(searchCardKey(SearchCardRow.LIBRARY, tsn) in shown) shouldBe false
	}

	test("a card no longer shown isn't found") {
		val shown = shownSearchCards(emptyList(), listOf(recording), emptyList())
		(searchCardKey(SearchCardRow.CHANNELS, tsn) in shown) shouldBe false
		(discoverCardKey(movie) in shown) shouldBe false
	}
})
