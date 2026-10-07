package org.jellyfin.androidtv.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

/** What a delete through Tentacle tells the user (#69). */
class LibraryDeleteTests : FunSpec({
	test("deleted") {
		deleteOutcome(200, """{"ok":true}""") shouldBe DeleteOutcome.Deleted
		deleteOutcome(204, null) shouldBe DeleteOutcome.Deleted
	}

	test("Tentacle's own reasons are shown: a failed Radarr/Sonarr delete, not the requester, not a download") {
		deleteOutcome(502, """{"detail":"Failed to delete 'Heat' from Radarr — files may still exist"}""") shouldBe
			DeleteOutcome.Refused("Failed to delete 'Heat' from Radarr — files may still exist")
		deleteOutcome(403, """{"detail":"You can only delete content you requested"}""") shouldBe
			DeleteOutcome.Refused("You can only delete content you requested")
		deleteOutcome(400, """{"detail":"Only downloaded content can be deleted from here"}""") shouldBe
			DeleteOutcome.Refused("Only downloaded content can be deleted from here")
	}

	test("a title Tentacle no longer has") {
		deleteOutcome(404, """{"detail":"Item not found in library"}""") shouldBe DeleteOutcome.NotInLibrary
		deleteOutcome(404, null) shouldBe DeleteOutcome.NotInLibrary
	}

	test("the plugin's own errors are never shown: exception text, plain text, other server errors") {
		deleteOutcome(500, """{"detail":"Connection refused (tentacle:8888)"}""") shouldBe DeleteOutcome.Unreachable
		deleteOutcome(400, "Tentacle URL not configured") shouldBe DeleteOutcome.Unreachable
		deleteOutcome(503, """{"detail":"x"}""") shouldBe DeleteOutcome.Unreachable
		deleteOutcome(502, "<html>Bad Gateway</html>") shouldBe DeleteOutcome.Unreachable
		deleteOutcome(422, """{"detail":[{"loc":["query"],"msg":"field required"}]}""") shouldBe DeleteOutcome.Unreachable
		deleteOutcome(403, null) shouldBe DeleteOutcome.Unreachable
	}

	test("messages") {
		val deleted = "Heat deleted"
		deleteOutcomeMessage("Heat", DeleteOutcome.Deleted, deleted) shouldBe "Heat deleted"
		deleteOutcomeMessage("Heat", DeleteOutcome.NotInLibrary, deleted) shouldBe "Heat is no longer in Tentacle's library"
		deleteOutcomeMessage("Heat", DeleteOutcome.Refused("Failed to delete 'Heat' from Radarr"), deleted) shouldBe
			"Couldn't delete Heat: Failed to delete 'Heat' from Radarr"
		deleteOutcomeMessage("Heat", DeleteOutcome.Unreachable, deleted) shouldBe "Couldn't delete Heat: can't reach Tentacle right now"
		deleteOutcomeMessage("Heat", DeleteOutcome.Failed, deleted) shouldBe "Couldn't delete Heat"
		deleteOutcomeMessage("Heat", deleteOutcome(500, """{"detail":"tentacle:8888"}"""), deleted) shouldNotContain "8888"
	}
})
