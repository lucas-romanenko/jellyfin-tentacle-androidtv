package org.jellyfin.androidtv.ui.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Continue Watching / Next Up rows that left Home empty are asked again and come back in place (#140). */
class HiddenStockRowsTests : FunSpec({
	val order = listOf("media", "resume", "nextup", "latest")

	test("an empty row that left Home is still asked again on resume") {
		HiddenStockRows.toRecheck(
			shown = listOf("media", "nextup", "latest"),
			stock = listOf("resume", "nextup", "latest"),
		) shouldBe listOf("media", "nextup", "latest", "resume")
	}

	test("rows on screen are not listed twice, and only stock rows come back") {
		HiddenStockRows.toRecheck(shown = order, stock = listOf("resume", "latest")) shouldBe order
		HiddenStockRows.toRecheck(shown = listOf("latest"), stock = listOf("latest")) shouldBe listOf("latest")
	}

	test("a returning row goes before the next row of the home order that is shown") {
		HiddenStockRows.returnIndex(order, "resume", shown = listOf("media", "nextup", "latest")) shouldBe 1
		HiddenStockRows.returnIndex(order, "nextup", shown = listOf("media", "resume", "latest")) shouldBe 2
	}

	test("both rows left: each comes back in its own place, whichever comes first") {
		HiddenStockRows.returnIndex(order, "nextup", shown = listOf("media", "latest")) shouldBe 1
		HiddenStockRows.returnIndex(order, "resume", shown = listOf("media", "nextup", "latest")) shouldBe 1
	}

	test("nothing after it is shown: after the previous one, else at the end") {
		HiddenStockRows.returnIndex(order, "latest", shown = listOf("media", "resume")) shouldBe 2
		HiddenStockRows.returnIndex(order, "latest", shown = listOf("now playing")) shouldBe 1
	}
})
