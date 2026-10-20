package org.jellyfin.androidtv.ui.home.mediabar

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Home's first hero load joins the one the start-up screen began, and only that one. */
class JoinsEarlyLoadTests : FunSpec({
	val started = 100_000L

	test("Home's full load moments after the start-up screen's: joins it") {
		joinsEarlyLoad(allowCache = false, earlyAt = started, now = started + 800) shouldBe true
	}

	test("no early load: Home loads the hero itself") {
		joinsEarlyLoad(allowCache = false, earlyAt = 0L, now = started) shouldBe false
	}

	test("an early load long ago: Home loads afresh") {
		val late = started + MediaBarSlideshowViewModel.EARLY_LOAD_JOIN_MS
		joinsEarlyLoad(allowCache = false, earlyAt = started, now = late) shouldBe false
	}

	test("a cache revalidation (refresh, resume) never joins: it runs as before") {
		joinsEarlyLoad(allowCache = true, earlyAt = started, now = started + 800) shouldBe false
	}
})
