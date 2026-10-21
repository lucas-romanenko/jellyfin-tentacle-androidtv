package org.jellyfin.androidtv.ui.search

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.data.repository.DiscoverItem
import org.jellyfin.androidtv.data.repository.DiscoverSearchOutcome
import org.jellyfin.androidtv.data.repository.discoverProblem
import kotlin.time.Duration.Companion.milliseconds

/**
 * Typing "TSN" on the TV searched "t", "ts" and "tsn" one after another; the slow Discover
 * search then answered "busy" and the results on screen disappeared behind that message.
 */
class DiscoverSearchStateTests : FunSpec({
	val busy = "Tentacle is busy and didn't answer in time. Try again in a moment."
	fun item(title: String, tmdbId: Int) = DiscoverItem(tmdbId = tmdbId, title = title)
	val tsn = listOf(item("TSN: The Movie", 1), item("TSNT", 2))

	test("a search waits for a pause in typing") {
		SearchQueries.debounce shouldBe 350.milliseconds
	}

	test("Discover needs two letters; Jellyfin searches from one") {
		SearchQueries.searchesDiscover("t") shouldBe false
		SearchQueries.searchesDiscover(" t ") shouldBe false
		SearchQueries.searchesDiscover("ts") shouldBe true
		SearchQueries.searchesLibrary("t") shouldBe true
		SearchQueries.searchesLibrary("   ") shouldBe false
	}

	test("a one-letter query clears the Discover row without searching") {
		val state = DiscoverSearchState(query = "ts", items = tsn).started("t")
		state.items.shouldBeEmpty()
		state.searching shouldBe false
		state.showRow shouldBe false
		state.message.shouldBeNull()
	}

	test("results stay on screen while the next query is searched") {
		val state = DiscoverSearchState(query = "ts", items = tsn).started("tsn")
		state.query shouldBe "tsn"
		state.items shouldBe tsn
		state.searching shouldBe true
		state.showRow shouldBe true
	}

	test("the first search shows the row as Searching") {
		val state = DiscoverSearchState().started("tsn")
		state.showRow shouldBe true
		state.items.shouldBeEmpty()
		state.message.shouldBeNull()
	}

	test("an answer replaces the results") {
		val state = DiscoverSearchState().started("tsn").answered("tsn", DiscoverSearchOutcome.Found(tsn))
		state.items shouldBe tsn
		state.searching shouldBe false
		state.message.shouldBeNull()
	}

	test("an empty answer is 'nothing matched' and clears the row") {
		val state = DiscoverSearchState(query = "ts", items = tsn).started("tsnx")
			.answered("tsnx", DiscoverSearchOutcome.Found(emptyList()))
		state.items.shouldBeEmpty()
		state.showRow shouldBe false
		state.message.shouldBeNull()
	}

	test("a late answer for a query typed past is ignored") {
		val typing = DiscoverSearchState().started("ts").started("tsn")
		val late = typing.answered("ts", DiscoverSearchOutcome.Found(listOf(item("Tsunami", 3))))
		late shouldBe typing
		late.answered("ts", DiscoverSearchOutcome.Failed(busy)) shouldBe typing
	}

	test("a failed or timed-out search keeps the last good results, with no error") {
		val state = DiscoverSearchState().started("ts").answered("ts", DiscoverSearchOutcome.Found(tsn))
			.started("tsn").answered("tsn", DiscoverSearchOutcome.Failed(busy))
		state.items shouldBe tsn
		state.searching shouldBe false
		state.message.shouldBeNull()
		state.showRow shouldBe true
	}

	test("the busy message shows only when nothing is on screen") {
		val state = DiscoverSearchState().started("tsn").answered("tsn", DiscoverSearchOutcome.Failed(busy))
		state.message shouldBe busy
		state.showRow shouldBe false
	}

	test("a new search hides the message until it answers") {
		val failed = DiscoverSearchState().started("tsn").answered("tsn", DiscoverSearchOutcome.Failed(busy))
		val retrying = failed.started("tsn 1")
		retrying.message.shouldBeNull()
		retrying.answered("tsn 1", DiscoverSearchOutcome.Found(tsn)).items shouldBe tsn
	}

	test("queries are compared trimmed") {
		val state = DiscoverSearchState().started(" tsn ").answered("tsn", DiscoverSearchOutcome.Found(tsn))
		state.items shouldBe tsn
	}

	test("the plugin's {error, message} becomes the line shown; no error is no line") {
		discoverProblem(null, null).shouldBeNull()
		discoverProblem("", busy).shouldBeNull()
		discoverProblem("timeout", busy) shouldBe busy
		discoverProblem("timeout", " ") shouldBe "Tentacle is unavailable right now."
	}
})
