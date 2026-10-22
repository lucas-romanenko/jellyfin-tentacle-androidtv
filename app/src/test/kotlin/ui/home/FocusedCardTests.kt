package org.jellyfin.androidtv.ui.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * A live update refreshed Home's rows and put the first card's backdrop and name behind the
 * card the focus was on (#14): the refresh reads the focused card instead.
 */
class FocusedCardTests : FunSpec({
	test("the focused card, not the row's first") {
		focusedCardIndex(selected = 5, size = 12) shouldBe 5
		focusedCardIndex(selected = 0, size = 12) shouldBe 0
	}

	test("a row that got shorter: the last card, where Leanback moves the focus") {
		focusedCardIndex(selected = 9, size = 4) shouldBe 3
	}

	test("unknown or empty: no guess") {
		focusedCardIndex(selected = null, size = 12).shouldBeNull()
		focusedCardIndex(selected = -1, size = 12).shouldBeNull()
		focusedCardIndex(selected = 2, size = 0).shouldBeNull()
	}
})
