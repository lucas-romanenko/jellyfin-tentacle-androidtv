package org.jellyfin.androidtv.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

/** Search shows TMDB titles only: the Live TV channels the server adds aren't Discover cards. */
class DiscoverSearchCardsTests : FunSpec({
	val json = Json {
		ignoreUnknownKeys = true
		isLenient = true
		coerceInputValues = true
	}

	test("channels the server puts first are left out, titles keep their order") {
		// The server's answer to /api/discover/search?type=all (channels first, then titles).
		val body = """
			{"items":[
			 {"media_type":"channel","title":"News 24","channel_id":17,"logo_url":null,"group_title":"News"},
			 {"tmdb_id":550,"title":"The Newsroom","year":"2012","media_type":"series","in_library":false},
			 {"tmdb_id":551,"title":"News of the World","year":"2020","media_type":"movie","in_library":true}
			]}
		""".trimIndent()
		val items = json.decodeFromString<DiscoverSearchResponse>(body).items
		items.size shouldBe 3
		discoverSearchCards(items).map { it.title } shouldBe listOf("The Newsroom", "News of the World")
	}

	test("a search with only titles is unchanged") {
		val items = listOf(DiscoverItem(tmdbId = 1, title = "A", mediaType = "movie"), DiscoverItem(tvdbId = 2, title = "B", mediaType = "series"))
		discoverSearchCards(items) shouldBe items
	}
})
