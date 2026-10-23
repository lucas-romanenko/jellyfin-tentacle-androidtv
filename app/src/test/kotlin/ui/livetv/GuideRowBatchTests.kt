package org.jellyfin.androidtv.ui.livetv

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

/** A page's rows go on screen a few per frame, and a rebuild keeps focus on the focused channel. */
class GuideRowBatchTests : FunSpec({
	test("the next batch is a frame's worth after the rows already added") {
		guideRowBatchEnd(added = 10, total = 75, perFrame = 10) shouldBe 20
	}

	test("the last batch stops at the page's end") {
		guideRowBatchEnd(added = 70, total = 75, perFrame = 10) shouldBe 75
	}

	test("a page already on screen has no more batches") {
		guideRowBatchEnd(added = 75, total = 75, perFrame = 10) shouldBe 75
	}

	test("at least one row per frame") {
		guideRowBatchEnd(added = 0, total = 5, perFrame = 0) shouldBe 1
	}

	test("rows rebuilt under focus in the rows: focus goes back to the focused channel") {
		val channel = UUID.randomUUID()
		guideRefocusChannel(focusInRows = true, focusedChannel = channel) shouldBe channel
	}

	test("rows rebuilt while focus is on the toggle: no channel takes focus") {
		guideRefocusChannel(focusInRows = false, focusedChannel = UUID.randomUUID()) shouldBe null
	}
})
