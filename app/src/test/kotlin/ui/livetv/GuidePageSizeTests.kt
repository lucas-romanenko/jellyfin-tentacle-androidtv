package org.jellyfin.androidtv.ui.livetv

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** A small-memory TV builds a shorter guide page. */
class GuidePageSizeTests : FunSpec({
	test("a small heap or a low-RAM device gets the short page") {
		guidePageSize(memoryClassMb = 192, isLowRamDevice = false) shouldBe GUIDE_PAGE_SIZE_SMALL
		guidePageSize(memoryClassMb = 256, isLowRamDevice = false) shouldBe GUIDE_PAGE_SIZE_SMALL
		guidePageSize(memoryClassMb = 512, isLowRamDevice = true) shouldBe GUIDE_PAGE_SIZE_SMALL
	}

	test("a roomy heap keeps the full page") {
		guidePageSize(memoryClassMb = 384, isLowRamDevice = false) shouldBe GUIDE_PAGE_SIZE_FULL
	}

	test("the full page is the guide's PAGE_SIZE") {
		GUIDE_PAGE_SIZE_FULL shouldBe LiveTvGuideFragment.PAGE_SIZE
	}
})
