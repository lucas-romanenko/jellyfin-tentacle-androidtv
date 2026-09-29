package org.jellyfin.androidtv.ui.playback.segment

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.ui.home.mediabar.SponsorBlockApi
import org.jellyfin.sdk.model.api.MediaSegmentType
import java.util.UUID

/** SponsorBlock segments for Tentacle's YouTube library, as media segments (#43). */
class SponsorBlockSegmentsTests : FunSpec({
	test("the video id is read from the youtube provider id, whatever its case") {
		youtubeVideoId(mapOf("youtube" to "aqz-KE-bpKQ")) shouldBe "aqz-KE-bpKQ"
		youtubeVideoId(mapOf("Tmdb" to "1", "YouTube" to "aqz-KE-bpKQ")) shouldBe "aqz-KE-bpKQ"
	}

	test("no or malformed ids give nothing") {
		youtubeVideoId(null) shouldBe null
		youtubeVideoId(mapOf("Tmdb" to "603")) shouldBe null
		youtubeVideoId(mapOf("youtube" to null)) shouldBe null
		youtubeVideoId(mapOf("youtube" to "not an id")) shouldBe null
	}

	test("categories map to media segment types") {
		sponsorBlockSegmentType("sponsor") shouldBe MediaSegmentType.COMMERCIAL
		sponsorBlockSegmentType("selfpromo") shouldBe MediaSegmentType.COMMERCIAL
		sponsorBlockSegmentType("interaction") shouldBe MediaSegmentType.COMMERCIAL
		sponsorBlockSegmentType("intro") shouldBe MediaSegmentType.INTRO
		sponsorBlockSegmentType("outro") shouldBe MediaSegmentType.OUTRO
		sponsorBlockSegmentType("preview") shouldBe MediaSegmentType.PREVIEW
		sponsorBlockSegmentType("music_offtopic") shouldBe null
		sponsorBlockSegmentType("filler") shouldBe null
	}

	test("every requested category maps to a type") {
		sponsorBlockCategories.all { sponsorBlockSegmentType(it) != null } shouldBe true
	}

	test("skip segments become media segments in ticks; mute, unknown and empty ones are left out") {
		val itemId = UUID.randomUUID()
		val segments = sponsorBlockToMediaSegments(
			itemId,
			listOf(
				SponsorBlockApi.Segment(12.5, 42.0, "sponsor", "skip"),
				SponsorBlockApi.Segment(50.0, 60.0, "sponsor", "mute"),
				SponsorBlockApi.Segment(70.0, 80.0, "music_offtopic", "skip"),
				SponsorBlockApi.Segment(90.0, 90.0, "intro", "skip"),
				SponsorBlockApi.Segment(0.0, 8.0, "intro", "skip"),
			),
		)
		segments.map { it.type } shouldBe listOf(MediaSegmentType.COMMERCIAL, MediaSegmentType.INTRO)
		segments[0].startTicks shouldBe 125_000_000L
		segments[0].endTicks shouldBe 420_000_000L
		segments.all { it.itemId == itemId } shouldBe true
	}
})
