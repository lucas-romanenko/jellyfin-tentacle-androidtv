package org.jellyfin.androidtv.ui.livetv

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The guide's rows go on screen as many per frame as fit the frame budget, by the measured cost. */
class GuideRowPacingTests : FunSpec({
	test("cost not measured yet (first open after start): one row") {
		guideRowsPerFrame(msPerRow = null, previousRows = null, budgetMs = 50, max = 12) shouldBe 1
	}

	test("a cold row dearer than the budget: still one row a frame") {
		guideRowsPerFrame(msPerRow = 300.0, previousRows = 1, budgetMs = 50, max = 12) shouldBe 1
	}

	test("rows that fit the budget") {
		guideRowsPerFrame(msPerRow = 10.0, previousRows = 8, budgetMs = 50, max = 12) shouldBe 5
	}

	test("one cheap frame at most doubles the next") {
		guideRowsPerFrame(msPerRow = 2.0, previousRows = 1, budgetMs = 50, max = 12) shouldBe 2
		guideRowsPerFrame(msPerRow = 2.0, previousRows = 2, budgetMs = 50, max = 12) shouldBe 4
	}

	test("a cost known from the last open: a whole batch at once, never more than a screenful") {
		guideRowsPerFrame(msPerRow = 2.0, previousRows = null, budgetMs = 50, max = 12) shouldBe 12
		guideRowsPerFrame(msPerRow = 0.0, previousRows = null, budgetMs = 50, max = 12) shouldBe 12
	}

	test("a screenful of zero still adds a row") {
		guideRowsPerFrame(msPerRow = 5.0, previousRows = null, budgetMs = 50, max = 0) shouldBe 1
	}

	test("a row's cost is the frame's time over its rows") {
		guideRowCost(frameMs = 120, rows = 4) shouldBe 30.0
		guideRowCost(frameMs = 120, rows = 0) shouldBe null
		guideRowCost(frameMs = -1, rows = 2) shouldBe 0.0
	}
})
