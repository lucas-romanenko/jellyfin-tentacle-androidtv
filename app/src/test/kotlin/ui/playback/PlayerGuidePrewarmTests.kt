package org.jellyfin.androidtv.ui.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

/** The guide over live TV, built ahead from the list the app has: its page and when it is old. */
class PlayerGuidePrewarmTests : FunSpec({
	test("the playing channel on the first page: the first page") {
		playerGuidePageStart(playingIndex = 3, count = 1700, pageSize = 25) shouldBe 0
	}

	test("further down: the playing channel near the middle of its page") {
		playerGuidePageStart(playingIndex = 100, count = 1700, pageSize = 25) shouldBe 88
	}

	test("near the end the page stays full") {
		playerGuidePageStart(playingIndex = 1699, count = 1700, pageSize = 25) shouldBe 1675
	}

	test("fewer channels than a page: from the first") {
		playerGuidePageStart(playingIndex = 4, count = 5, pageSize = 25) shouldBe 0
	}

	test("a page is old once the next half hour begins") {
		val start = LocalDateTime.of(2026, 10, 25, 20, 30)
		playerGuideStale(null, start) shouldBe true
		playerGuideStale(start, start.plusMinutes(29)) shouldBe false
		playerGuideStale(start, start.plusMinutes(30).plusSeconds(1)) shouldBe true
		msUntilPlayerGuideStale(start, start.plusMinutes(20)) shouldBe 600_001L
		msUntilPlayerGuideStale(start, start.plusMinutes(45)) shouldBe 1L
	}
})
