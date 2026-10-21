package org.jellyfin.androidtv.ui.itemdetail.v2

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The details backdrop is loaded small for the CPU blur, and lazy rows never repeat a key. */
class DetailsPerfTests : FunSpec({
	test("before Android 12 a blurred backdrop is loaded small for the CPU blur") {
		val spec = detailsBackdropSpec(sdkInt = 28, blurAmount = 10)
		spec.cpuBlur shouldBe true
		spec.maxWidth shouldBe 480
		spec.decodeWidth shouldBe 480
		spec.decodeHeight shouldBe 270
	}

	test("without blur, or with the GPU blur, the backdrop stays full size") {
		detailsBackdropSpec(sdkInt = 28, blurAmount = 0).cpuBlur shouldBe false
		detailsBackdropSpec(sdkInt = 28, blurAmount = 0).maxWidth shouldBe DETAILS_BACKDROP_WIDTH
		detailsBackdropSpec(sdkInt = 31, blurAmount = 10).cpuBlur shouldBe false
		detailsBackdropSpec(sdkInt = 31, blurAmount = 10).maxWidth shouldBe DETAILS_BACKDROP_WIDTH
	}

	test("unique keys keep the first key and number the repeats") {
		uniqueKeys(listOf("a", "b", "a", "a")) shouldBe listOf("a", "b", "a#1", "a#2")
		uniqueKeys(emptyList<String>()) shouldBe emptyList()
	}
})
