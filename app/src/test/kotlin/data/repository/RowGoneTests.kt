package org.jellyfin.androidtv.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Which answers from the row endpoint drop a home row and delete its saved copy (#49). */
class RowGoneTests : FunSpec({
	test("the plugin's answers for a playlist the user can't see drop the row") {
		rowGoneOn(400) shouldBe true
		rowGoneOn(403) shouldBe true
		rowGoneOn(404) shouldBe true
	}

	test("a busy server, a proxy's limit or a sign-in problem keeps the row's saved copy") {
		for (code in listOf(401, 408, 409, 425, 429, 499)) rowGoneOn(code) shouldBe false
	}

	test("server errors and success are not 'gone'") {
		for (code in listOf(200, 204, 500, 502, 503, 504)) rowGoneOn(code) shouldBe false
	}
})
