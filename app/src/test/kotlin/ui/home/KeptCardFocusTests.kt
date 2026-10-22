package org.jellyfin.androidtv.ui.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * A live update that changed a row's shape (poster/wide) or moved rows put the focus on the
 * row's first card: it stays on the card of the same item, else at the same place.
 */
class KeptCardFocusTests : FunSpec({
	val row = listOf("a", "b", "c", "d", "the-crash", "f")

	test("the same item, where it is now") {
		keptCardIndex("the-crash", 4, row) shouldBe 4
		keptCardIndex("the-crash", 4, listOf("new") + row) shouldBe 5
		keptCardIndex("c", 2, row.reversed()) shouldBe 3
	}

	test("the item left the row: the same place, or the last card of a shorter row") {
		keptCardIndex("gone", 2, row) shouldBe 2
		keptCardIndex("gone", 9, row) shouldBe 5
		keptCardIndex(null, 3, row) shouldBe 3
	}

	test("an empty row or no focus: nothing to keep") {
		keptCardIndex("a", 0, emptyList<String>()).shouldBeNull()
		keptCardIndex("a", -1, row).shouldBeNull()
	}

	test("pictures fetched ahead: the cards around the focused one") {
		cardsToWarm(size = 20, focused = 4) shouldBe 2 until 12
		cardsToWarm(size = 20, focused = null) shouldBe 0 until 10
		cardsToWarm(size = 20, focused = 1) shouldBe 0 until 10
		cardsToWarm(size = 20, focused = 18) shouldBe 16 until 20
		cardsToWarm(size = 3, focused = 2) shouldBe 0 until 3
		cardsToWarm(size = 0, focused = 0) shouldBe IntRange.EMPTY
	}
})
