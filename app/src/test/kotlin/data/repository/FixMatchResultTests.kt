package org.jellyfin.androidtv.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

/** What "Fix it" did to the item: only a removed duplicate leaves the home rows (g-fixit N2). */
class FixMatchResultTests : FunSpec({
	// The same settings TentacleRepository parses the plugin's answers with
	val json = Json {
		ignoreUnknownKeys = true
		isLenient = true
		coerceInputValues = true
	}
	fun parse(body: String) = json.decodeFromString<ArrActionResult>(body).copy(ok = true)

	test("a fix in place keeps the item") {
		fixMatchRemovedItem(parse("""{"ok":true,"title":"Heat","year":1995,"tmdb_id":949,"message":"Fixed: this is Heat (1995)."}""")) shouldBe false
	}

	test("a copy removed as a duplicate of a film already in the library is gone") {
		fixMatchRemovedItem(parse("""{"ok":true,"merged":true,"message":"That film is already in your library — removed this duplicate copy"}""")) shouldBe true
	}

	test("an older server without the flag, or a null, keeps the item") {
		fixMatchRemovedItem(parse("""{"ok":true}""")) shouldBe false
		fixMatchRemovedItem(parse("""{"ok":true,"merged":null}""")) shouldBe false
	}

	test("a failed fix never hides the item") {
		fixMatchRemovedItem(ArrActionResult(ok = false, merged = true)) shouldBe false
	}
})
