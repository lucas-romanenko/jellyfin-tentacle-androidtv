package org.jellyfin.androidtv.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import java.util.UUID

/** The home version a push carries, and the newest one the app knows of (androidtv audit H2, H3). */
class HomeVersionTrackerTests : FunSpec({
	val me = UUID.fromString("942bf12a-d315-4b8d-96fa-1b62345ab203")
	val someoneElse = UUID.fromString("00000000-0000-0000-0000-000000000001")

	test("a save of my home carries my id without dashes") {
		homePushFrom(listOf("tentacle-home:942bf12ad3154b8d96fa1b62345ab203:1760950000123"), me) shouldBe
			HomePush(1760950000123, everyone = false)
	}

	test("a change for everyone is mine too") {
		homePushFrom(listOf("tentacle-home:*:42"), me) shouldBe HomePush(42, everyone = true)
	}

	test("another user's save, a plain library change and junk are no home version") {
		homePushFrom(listOf("tentacle-home:942bf12ad3154b8d96fa1b62345ab203:5"), someoneElse).shouldBeNull()
		homePushFrom(emptyList(), me).shouldBeNull()
		homePushFrom(listOf("f137a2dd21bbc1b99aa5c0f6bf02a805"), me).shouldBeNull()
		homePushFrom(listOf("tentacle-home:*:", "tentacle-home:*:x", "tentacle-home:*:0", "tentacle-home:*:-3"), me).shouldBeNull()
		homePushFrom(listOf("tentacle-home:*:42"), null) shouldBe HomePush(42, everyone = true)
	}

	test("the tracker keeps the newest version, whatever order answers come in") {
		val tracker = HomeVersionTracker()
		tracker.known.value shouldBe 0L
		tracker.noteVersion(10)
		tracker.onLibraryChanged(HomeChange(HomePush(30, everyone = false)))
		tracker.noteVersion(20) // a slow answer that left before the push
		tracker.known.value shouldBe 30L
		tracker.noteVersion(0) // an older plugin: no version
		tracker.known.value shouldBe 30L
	}

	test("an older plugin's answers parse, with no version") {
		val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
		json.decodeFromString<TentacleSectionsResponse>("""{"enabled":true,"sections":[]}""").homeVersion shouldBe 0L
		json.decodeFromString<ToolbarResponse>("""{"buttons":[]}""").homeVersion shouldBe 0L
		json.decodeFromString<TentacleSectionsResponse>("""{"enabled":true,"sections":[],"homeVersion":1760950000123}""")
			.homeVersion shouldBe 1760950000123L
		json.decodeFromString<ToolbarResponse>("""{"buttons":[],"homeVersion":7}""").homeVersion shouldBe 7L
	}
})
