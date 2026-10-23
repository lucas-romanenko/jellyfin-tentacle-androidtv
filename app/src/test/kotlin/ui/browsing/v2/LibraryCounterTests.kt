package org.jellyfin.androidtv.ui.browsing.v2

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The library grid's counter showed "100 | 14532" (the loaded page) whatever poster had focus. */
class LibraryCounterTests : FunSpec({
	test("shows the focused poster's number from 1 and the total") {
		libraryCounterText(focusedIndex = 0, loadedItems = 100, totalItems = 14532) shouldBe "1 | 14532"
		libraryCounterText(focusedIndex = 14, loadedItems = 100, totalItems = 14532) shouldBe "15 | 14532"
		libraryCounterText(focusedIndex = 149, loadedItems = 200, totalItems = 14532) shouldBe "150 | 14532"
	}

	test("the last poster is the total") {
		libraryCounterText(focusedIndex = 41, loadedItems = 42, totalItems = 42) shouldBe "42 | 42"
	}

	test("nothing loaded shows nothing") {
		libraryCounterText(focusedIndex = 0, loadedItems = 0, totalItems = 0) shouldBe ""
	}

	test("without a total from the server the loaded count stands in") {
		libraryCounterText(focusedIndex = 2, loadedItems = 10, totalItems = 0) shouldBe "3 | 10"
	}

	test("a stale index never shows past the end") {
		libraryCounterText(focusedIndex = 99, loadedItems = 5, totalItems = 5) shouldBe "5 | 5"
	}
})
