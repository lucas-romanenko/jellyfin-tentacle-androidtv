package org.jellyfin.androidtv.ui.composable.item

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** A card preview starts only once the D-pad has rested 2 s (H6). */
class PreviewRestTests : FunSpec({
	test("no key for longer than the rest: start now") {
		restRemaining(now = 10_000, lastKeyAt = 7_000, rest = 2_000) shouldBe 0
		restRemaining(now = 10_000, lastKeyAt = 8_000, rest = 2_000) shouldBe 0
	}

	test("a key pressed meanwhile: wait until it has rested 2 s") {
		restRemaining(now = 10_000, lastKeyAt = 9_500, rest = 2_000) shouldBe 1_500
		restRemaining(now = 10_000, lastKeyAt = 10_000, rest = 2_000) shouldBe 2_000
	}

	test("never a key since start: start now") {
		restRemaining(now = 10_000, lastKeyAt = 0, rest = 2_000) shouldBe 0
	}

	test("the rest is about 2 s, not the old half second") {
		PREVIEW_REST_MS shouldBe 2_000L
	}
})
