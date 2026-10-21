package org.jellyfin.androidtv.ui.home.mediabar

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The hero trailer lingers, stopped, after focus leaves the hero, instead of being torn down in that frame. */
class TrailerLingerTests : FunSpec({
	val stream = YouTubeStreamResolver.StreamInfo(videoUrl = "https://example.invalid/a.mp4", audioUrl = null, isVideoOnly = false)
	val a = TrailerPreviewInfo(youtubeVideoId = "a", streamInfo = stream)
	val b = TrailerPreviewInfo(youtubeVideoId = "b", streamInfo = stream)

	test("focus leaves the hero: the trailer stays shown, lingering, until finish") {
		val linger = TrailerLinger()
		linger.update(a)
		linger.update(null)
		linger.shown shouldBe a
		linger.lingering shouldBe true
		linger.finish()
		linger.shown shouldBe null
		linger.lingering shouldBe false
	}

	test("back on the hero while it lingers: a new player (epoch), not the stopped one") {
		val linger = TrailerLinger()
		linger.update(a)
		linger.update(null)
		linger.update(a)
		linger.shown shouldBe a
		linger.lingering shouldBe false
		linger.epoch shouldBe 1
		linger.finish() // the old linger's timer: nothing to drop any more
		linger.shown shouldBe a
	}

	test("the next slide's trailer replaces the current one straight away, as before") {
		val linger = TrailerLinger()
		linger.update(a)
		linger.update(b)
		linger.shown shouldBe b
		linger.lingering shouldBe false
		linger.epoch shouldBe 0
	}

	test("nothing was shown: nothing lingers") {
		val linger = TrailerLinger()
		linger.update(null)
		linger.lingering shouldBe false
		linger.shown shouldBe null
	}

	test("repeated idle states while lingering don't restart the linger") {
		val linger = TrailerLinger()
		linger.update(a)
		linger.update(null)
		linger.update(null)
		linger.epoch shouldBe 0
		linger.lingering shouldBe true
	}

	test("only Buffering and Playing with a stream are playable") {
		TrailerPreviewState.Playing(a).playableInfo() shouldBe a
		TrailerPreviewState.Buffering(b).playableInfo() shouldBe b
		TrailerPreviewState.Buffering(TrailerPreviewInfo(youtubeVideoId = "c")).playableInfo() shouldBe null
		TrailerPreviewState.Idle.playableInfo() shouldBe null
		TrailerPreviewState.WaitingToPlay.playableInfo() shouldBe null
	}
})
