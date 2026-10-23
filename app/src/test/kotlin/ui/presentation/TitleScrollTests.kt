package org.jellyfin.androidtv.ui.presentation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * A focused card's title showed cut at both ends ("erial Killer Conne") after its row was
 * rebuilt: the scroll that shows a centred title from its first letter.
 */
class TitleScrollTests : FunSpec({
	test("a title wider than the card starts at its first letter") {
		textStartScrollX(left = 1000, right = 1300, width = 200, rightToLeft = false) shouldBe 1000
	}

	test("a right-to-left title wider than the card starts at its right edge") {
		textStartScrollX(left = 1000, right = 1300, width = 200, rightToLeft = true) shouldBe 1100
	}

	test("a title that fits stays centred") {
		textStartScrollX(left = 1000, right = 1100, width = 200, rightToLeft = false) shouldBe 950
		textStartScrollX(left = 1000, right = 1100, width = 200, rightToLeft = true) shouldBe 950
	}
})
