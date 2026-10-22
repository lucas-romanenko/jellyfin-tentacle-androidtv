package org.jellyfin.androidtv.ui.livetv

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

private fun id(n: Int): UUID = UUID.fromString("%08d-0000-4000-8000-000000000000".format(n))

/** All shows its first page before the whole list has come: the page's rows stay when it still starts the list. */
class GuideFirstPageTests : FunSpec({
	val all = (1..100).map(::id)

	test("the first page of the whole list stays") {
		isFirstPageOf(all.take(25), all) shouldBe true
	}

	test("a page that is the whole list stays") {
		isFirstPageOf(all.take(10), all.take(10)) shouldBe true
	}

	test("an empty page stays") {
		isFirstPageOf(emptyList(), all) shouldBe true
	}

	test("a channel added at the top in between: the page is shown anew") {
		isFirstPageOf(all.take(25), listOf(id(999)) + all) shouldBe false
	}

	test("a channel of the page gone in between: the page is shown anew") {
		isFirstPageOf(all.take(25), all.drop(1)) shouldBe false
	}

	test("a page longer than the whole list: the page is shown anew") {
		isFirstPageOf(all.take(25), all.take(20)) shouldBe false
	}
})
