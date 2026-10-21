package org.jellyfin.androidtv.di

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The poster memory cache stays small on a large-heap TV. */
class ImageMemoryCacheTests : FunSpec({
	val mb = 1024L * 1024

	test("a 512 MB large heap gets 64 MB, not 30%") {
		imageMemoryCacheBytes(512 * mb) shouldBe 64 * mb
	}

	test("a small heap keeps 30%") {
		imageMemoryCacheBytes(128 * mb) shouldBe (128 * mb * 0.30).toLong()
	}
})
