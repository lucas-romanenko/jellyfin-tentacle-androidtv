package org.jellyfin.androidtv.ui.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/** Which Tentacle rows the loading screen waits for at the first load (H4). */
class FirstScreenRowsTests : FunSpec({
	test("the first two content rows, when they are Tentacle rows") {
		firstScreenRows(listOf("a", "b", "c", null, "d")) shouldBe setOf("a", "b")
	}

	test("a stock row among the first two is not waited for (it fills itself)") {
		firstScreenRows(listOf(null, "b", "c")) shouldBe setOf("b")
		firstScreenRows(listOf("a", null, "c")) shouldBe setOf("a")
	}

	test("no Tentacle row on the first screen: nothing to wait for") {
		firstScreenRows(listOf(null, null, "c")).shouldBeEmpty()
		firstScreenRows(emptyList()).shouldBeEmpty()
	}
})
