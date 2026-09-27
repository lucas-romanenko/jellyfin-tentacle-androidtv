package org.jellyfin.androidtv.ui.discover

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jellyfin.androidtv.data.repository.AddResult

/** What the episode picker reports after adding picked episodes to Sonarr (#21). */
class AddEpisodesOutcomeTests : FunSpec({
	test("episodes added is done") {
		addEpisodesOutcome(AddResult(added = 1), 3) shouldBe (true to "Added 3 episodes to Sonarr")
	}

	test("a series already in Sonarr is not done: the picked episodes were not applied") {
		val (ok, message) = addEpisodesOutcome(AddResult(alreadyExists = 1), 3)
		ok shouldBe false
		message shouldContain "Manage Episodes"
	}

	test("a refusal shows the server's reason") {
		addEpisodesOutcome(AddResult(failed = 1, detail = "Sonarr refused the path"), 3) shouldBe
			(false to "Sonarr refused the path")
		addEpisodesOutcome(AddResult(failed = 1), 3) shouldBe (false to "Failed to add to Sonarr")
	}

	test("a transport error says so") {
		addEpisodesOutcome(AddResult(error = "timeout"), 3) shouldBe (false to "Error: timeout")
	}
})
