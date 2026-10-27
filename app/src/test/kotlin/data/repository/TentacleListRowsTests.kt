package org.jellyfin.androidtv.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Continue Watching and Recordings are Tentacle's own rows (2026-10-26): the plugin works them out
 * once for the web and the TV, and the TV draws them like playlist rows, in the shape Tentacle says.
 */
class TentacleListRowsTests : FunSpec({
	fun builtin(id: String, shape: String = "") = TentacleSection(type = "builtin", sectionId = id, displayText = id, shape = shape)

	test("a plugin that serves them: Continue Watching and Recordings become rows with their shape") {
		val sections = TentacleSectionsResponse(
			enabled = true,
			sections = listOf(
				builtin("resumevideo", "wide"),
				builtin("onnow", "onnow"),
				builtin("recordings", "poster"),
				TentacleSection(type = "row", playlistId = "abc", shape = "poster"),
			),
		).withTentacleListRows().sections
		sections.map { it.type to (it.playlistId ?: it.sectionId) } shouldBe listOf(
			"row" to "tentacle-resumevideo",
			"builtin" to "onnow",
			"row" to "tentacle-recordings",
			"row" to "abc",
		)
		sections[0].shape shouldBe "wide"
		sections[2].shape shouldBe "poster"
	}

	test("an older plugin's built-in rows (no shape) stay as they are") {
		val old = TentacleSectionsResponse(enabled = true, sections = listOf(builtin("resumevideo"), builtin("nextup")))
		old.withTentacleListRows() shouldBe old
	}

	test("their items come from the plugin's own endpoints, a playlist's from its section") {
		sectionItemsPath("tentacle-resumevideo") shouldBe "/TentacleHome/ContinueWatching"
		sectionItemsPath("tentacle-recordings") shouldBe "/TentacleHome/Recordings"
		sectionItemsPath("0123456789abcdef0123456789abcdef") shouldBe "/TentacleHome/Section/0123456789abcdef0123456789abcdef"
		sectionItemsPath("tentacle-unknown") shouldBe "/TentacleHome/Section/tentacle-unknown"
	}
})
