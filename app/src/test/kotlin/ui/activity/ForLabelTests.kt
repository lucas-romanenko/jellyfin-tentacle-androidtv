package org.jellyfin.androidtv.ui.activity

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.jellyfin.androidtv.data.repository.ActivityResponse

/** Who an Activity card is for. */
class ForLabelTests : FunSpec({
	test("a server that names people: someone else's item says whose") {
		forLabel(forNames = true, forName = "Abbie") shouldBe "for Abbie"
		forLabel(forNames = true, forName = "Abbie", requestedBy = "Abbie") shouldBe "for Abbie"
	}

	test("the viewer's own item says nothing, even with a requester") {
		forLabel(forNames = true, forName = null) shouldBe null
		forLabel(forNames = true, forName = " ") shouldBe null
		forLabel(forNames = true, forName = null, requestedBy = "Lucas") shouldBe null
	}

	test("an older server: the requester shows as before, nothing else") {
		forLabel(forNames = false, forName = null, requestedBy = "Abbie") shouldBe "Abbie"
		forLabel(forNames = false, forName = null) shouldBe null
		forLabel(forNames = false, forName = "Abbie") shouldBe null
	}

	test("for_names and each item's for are read from the answer") {
		val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
		val response = json.decodeFromString<ActivityResponse>(
			"""{"for_names":true,"downloads":[{"title":"Dune","for":"Abbie"},{"title":"Alien"}],
			"searching":[{"title":"Heat","for":"Ben"}],"unreleased":[{"title":"Up","for":"Ben"}],
			"recently_downloaded":[{"title":"Jaws","for":"Ben"}],"coming_up":[{"title":"Lost","for":"Ben"}]}"""
		)
		response.forNames shouldBe true
		response.downloads.map { it.forName } shouldBe listOf("Abbie", null)
		response.searching.single().forName shouldBe "Ben"
		response.unreleased.single().forName shouldBe "Ben"
		response.recentlyDownloaded.single().forName shouldBe "Ben"
		response.comingUp.single().forName shouldBe "Ben"
		json.decodeFromString<ActivityResponse>("{}").forNames shouldBe false
	}
})
