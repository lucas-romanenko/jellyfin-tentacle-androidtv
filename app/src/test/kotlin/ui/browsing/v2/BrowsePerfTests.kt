package org.jellyfin.androidtv.ui.browsing.v2

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.util.apiclient.JellyfinImage
import org.jellyfin.androidtv.util.apiclient.JellyfinImageSource
import org.jellyfin.sdk.model.api.ImageType
import java.util.UUID

/** Card images are asked for at the card's size, and paged grids never repeat a key. */
class BrowsePerfTests : FunSpec({
	fun image(type: ImageType, aspectRatio: Float? = null) = JellyfinImage(
		item = UUID.randomUUID(),
		source = JellyfinImageSource.ITEM,
		type = type,
		tag = "tag",
		blurHash = null,
		aspectRatio = aspectRatio,
		index = null,
	)

	test("a poster in a poster card is asked for at the card's height") {
		cardImageMaxHeight(cardWidthPx = 240, cardHeightPx = 360, imageAspectRatio = 2f / 3f) shouldBe 360
	}

	test("a picture narrower than the card is asked for tall enough to cover its width") {
		// a 16:9 card showing a poster: scaled by width, so 400 / (2/3) = 600 px tall
		cardImageMaxHeight(cardWidthPx = 400, cardHeightPx = 225, imageAspectRatio = 2f / 3f) shouldBe 600
	}

	test("an unknown aspect ratio asks for the larger card side") {
		cardImageMaxHeight(cardWidthPx = 400, cardHeightPx = 225, imageAspectRatio = null) shouldBe 400
		cardImageMaxHeight(cardWidthPx = 240, cardHeightPx = 360, imageAspectRatio = 0f) shouldBe 360
	}

	test("a very tall picture in a wide card is capped") {
		cardImageMaxHeight(cardWidthPx = 720, cardHeightPx = 124, imageAspectRatio = 0.1f) shouldBe 1440
	}

	test("thumbs, backdrops and banners have their usual shape when Jellyfin gives none") {
		expectedImageAspectRatio(image(ImageType.THUMB)) shouldBe 16f / 9f
		expectedImageAspectRatio(image(ImageType.BACKDROP)) shouldBe 16f / 9f
		expectedImageAspectRatio(image(ImageType.BANNER)) shouldBe 1000f / 185f
		expectedImageAspectRatio(image(ImageType.PRIMARY)) shouldBe null
		expectedImageAspectRatio(image(ImageType.PRIMARY, aspectRatio = 0.68f)) shouldBe 0.68f
	}

	test("a page appends only items not already shown, in order") {
		appendDistinctBy(listOf(1, 2, 3), listOf(3, 4, 2, 5)) { it } shouldBe listOf(1, 2, 3, 4, 5)
		appendDistinctBy(emptyList(), listOf(1, 1, 2)) { it } shouldBe listOf(1, 2)
	}

	test("a page with nothing new keeps the same list") {
		val shown = listOf(1, 2)
		(appendDistinctBy(shown, listOf(2, 1)) { it } === shown) shouldBe true
	}
})
