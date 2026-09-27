package org.jellyfin.androidtv.ui.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.data.repository.SectionFetch
import org.jellyfin.androidtv.data.repository.SectionSource
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/** What an in-place home refresh does with one row's fresh answer (#49, #54). */
class TentacleRowRefreshTests : FunSpec({
	fun items(n: Int) = List(n) { BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.MOVIE) }
	fun server(n: Int) = SectionFetch(items(n), SectionSource.SERVER)

	test("a normal answer replaces the row") {
		TentacleRowRefresh.decide(20, server(20), limit = 20, emptyAnswersInARow = 0) shouldBe RowRefreshAction.REPLACE
		TentacleRowRefresh.decide(20, server(18), limit = 20, emptyAnswersInARow = 0) shouldBe RowRefreshAction.REPLACE
	}

	test("a row the dashboard shortened is applied: the answer fills the new limit") {
		TentacleRowRefresh.decide(20, server(5), limit = 5, emptyAnswersInARow = 0) shouldBe RowRefreshAction.REPLACE
	}

	test("a big shrink below the limit is a playlist caught mid-rebuild, and kept") {
		TentacleRowRefresh.decide(20, server(3), limit = 20, emptyAnswersInARow = 0) shouldBe RowRefreshAction.KEEP
		// An older plugin says no limit: the old guard still holds.
		TentacleRowRefresh.decide(20, server(5), limit = 0, emptyAnswersInARow = 0) shouldBe RowRefreshAction.KEEP
	}

	test("a real shrink below the limit is applied once the same answer comes twice (#54)") {
		TentacleRowRefresh.decide(20, server(8), limit = 20, emptyAnswersInARow = 0) shouldBe RowRefreshAction.KEEP
		TentacleRowRefresh.decide(20, server(8), limit = 20, emptyAnswersInARow = 0, sameShrunkAnswerAgain = true) shouldBe
			RowRefreshAction.REPLACE
		// A saved copy is never evidence, repeated or not.
		TentacleRowRefresh.decide(20, SectionFetch(items(8), SectionSource.SAVED_COPY), 20, 0, sameShrunkAnswerAgain = true) shouldBe
			RowRefreshAction.KEEP
	}

	test("one empty answer is kept, a second in a row takes the row away") {
		TentacleRowRefresh.decide(20, server(0), limit = 20, emptyAnswersInARow = 1) shouldBe RowRefreshAction.KEEP
		TentacleRowRefresh.decide(20, server(0), limit = 20, emptyAnswersInARow = 2) shouldBe RowRefreshAction.DROP
	}

	test("a server that did not answer never changes the row") {
		TentacleRowRefresh.decide(20, SectionFetch(items(20), SectionSource.SAVED_COPY), 20, 0) shouldBe RowRefreshAction.KEEP
		TentacleRowRefresh.decide(20, SectionFetch(emptyList(), SectionSource.SAVED_COPY), 20, 0) shouldBe RowRefreshAction.KEEP
	}

	test("a playlist the user can no longer see leaves") {
		TentacleRowRefresh.decide(20, SectionFetch(emptyList(), SectionSource.GONE), 20, 0) shouldBe RowRefreshAction.DROP
	}
})
