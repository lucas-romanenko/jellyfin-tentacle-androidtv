package org.jellyfin.androidtv.ui.discover

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.data.repository.QualityProfile
import org.jellyfin.androidtv.data.repository.qualityProfileChoices
import org.jellyfin.androidtv.data.repository.qualityProfileLabel

/** The Add dialog's profile picker: the server's default first, a pick only when chosen (#61). */
class QualityProfileChoiceTests : FunSpec({
	val json = Json { ignoreUnknownKeys = true }
	val profiles = json.decodeFromString<List<QualityProfile>>(
		"""[{"id": 4, "name": "HD-1080p", "is_default": true}, {"id": 5, "name": "Ultra-HD", "is_default": false}]"""
	)

	test("the server's is_default is read") {
		profiles.map { it.isDefault } shouldBe listOf(true, false)
	}

	test("an older server without is_default still parses") {
		json.decodeFromString<List<QualityProfile>>("""[{"id": 1, "name": "Any"}]""").single().isDefault shouldBe false
	}

	test("the choices start with the server's default, then every profile") {
		qualityProfileChoices(profiles) shouldBe listOf(null, 4, 5)
		qualityProfileChoices(emptyList()) shouldBe listOf(null)
	}

	test("the default choice names the server's default profile") {
		qualityProfileLabel(profiles, null) shouldBe "Default (HD-1080p)"
		qualityProfileLabel(profiles.map { it.copy(isDefault = false) }, null) shouldBe "Default profile"
	}

	test("a picked profile shows its name") {
		qualityProfileLabel(profiles, 5) shouldBe "Ultra-HD"
	}
})
