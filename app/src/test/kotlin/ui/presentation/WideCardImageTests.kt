package org.jellyfin.androidtv.ui.presentation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.util.apiclient.itemImages
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import java.util.UUID

/** Wide (16:9) cards pick Jellyfin Web's picture and never crop a poster into 16:9. */
class WideCardImageTests : FunSpec({
	fun item(
		type: BaseItemKind = BaseItemKind.MOVIE,
		thumb: Boolean = false,
		backdrop: Boolean = false,
		primary: Boolean = true,
		primaryAspect: Double? = 2.0 / 3.0,
	) = BaseItemDto(
		id = UUID.randomUUID(),
		type = type,
		imageTags = buildMap {
			if (primary) put(ImageType.PRIMARY, "p")
			if (thumb) put(ImageType.THUMB, "t")
		},
		backdropImageTags = if (backdrop) listOf("b0", "b1") else emptyList(),
		primaryImageAspectRatio = primaryAspect,
	)

	fun BaseItemDto.wide() = wideCardImage(this, itemImages[ImageType.PRIMARY])

	test("a Thumb comes first and fills the card") {
		val image = item(thumb = true, backdrop = true).wide()!!
		image.type shouldBe ImageType.THUMB
		fillsWideCard(image) shouldBe true
	}

	test("a poster and a backdrop (Who Is Martin Mull?): the first backdrop, filling the card") {
		val image = item(backdrop = true).wide()!!
		image.type shouldBe ImageType.BACKDROP
		image.index shouldBe 0
		image.tag shouldBe "b0"
		fillsWideCard(image) shouldBe true
	}

	test("only a poster: the poster, fitted whole instead of cropped") {
		val image = item().wide()!!
		image.type shouldBe ImageType.PRIMARY
		fillsWideCard(image) shouldBe false
	}

	test("an episode's own 16:9 still fills the card") {
		val image = item(type = BaseItemKind.EPISODE, primaryAspect = 16.0 / 9.0).wide()!!
		image.type shouldBe ImageType.PRIMARY
		fillsWideCard(image) shouldBe true
	}

	test("a Primary fills only within 20% of 16:9; an unknown aspect is fitted") {
		fillsWideCard(item(primaryAspect = 1.6).wide()!!) shouldBe true
		fillsWideCard(item(primaryAspect = 4.0 / 3.0).wide()!!) shouldBe false
		fillsWideCard(item(primaryAspect = 1.0).wide()!!) shouldBe false
		fillsWideCard(item(primaryAspect = null).wide()!!) shouldBe false
	}

	test("no image at all: nothing, so the card shows its type icon") {
		item(primary = false).wide().shouldBeNull()
	}
})
