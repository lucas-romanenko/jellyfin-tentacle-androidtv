package org.jellyfin.androidtv.ui.livetv

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.data.repository.LivePreviewAnswer
import org.jellyfin.androidtv.data.repository.parseLivePreview
import java.time.LocalDateTime

class GuidePreviewTests : FunSpec({
	val now = LocalDateTime.of(2026, 10, 24, 20, 30)

	test("a programme on now previews live; a later one, an ended one or no guide data shows the picture") {
		guidePreviewKind(now.minusMinutes(5), now.plusMinutes(25), now, noData = false) shouldBe GuidePreviewKind.LIVE
		guidePreviewKind(now.plusMinutes(30), now.plusMinutes(60), now, noData = false) shouldBe GuidePreviewKind.PICTURE
		guidePreviewKind(now.minusMinutes(60), now, now, noData = false) shouldBe GuidePreviewKind.PICTURE
		guidePreviewKind(now.minusMinutes(5), now.plusMinutes(25), now, noData = true) shouldBe GuidePreviewKind.PICTURE
		guidePreviewKind(null, null, now, noData = false) shouldBe GuidePreviewKind.PICTURE
	}

	test("the box is there only while previews may run; not known yet counts as no") {
		guidePreviewBoxShown(true) shouldBe true
		guidePreviewBoxShown(false) shouldBe false
		guidePreviewBoxShown(null) shouldBe false
	}

	test("the server's answer") {
		parseLivePreview(200, """{"previews":true,"limit":8,"active":3,"free":5,"extra":1}""") shouldBe
			LivePreviewAnswer(previews = true, limit = 8, active = 3, free = 5)
		parseLivePreview(200, """{"previews":false,"limit":8,"active":7,"free":1}""")?.previews shouldBe false
	}

	test("the plugin without Tentacle answers no, and the server's extra fields are read") {
		parseLivePreview(200, """{"previews":false,"limit":0,"active":0,"free":0,"recordings_soon":0,"known":false}""") shouldBe
			LivePreviewAnswer(previews = false, known = false)
		parseLivePreview(200, """{"previews":false,"limit":8,"active":5,"free":1,"recordings_soon":2,"known":true}""")?.recordingsSoon shouldBe 2
	}

	test("no answer (no plugin, an error, an older server): null, so the guide keeps what it shows") {
		parseLivePreview(404, "") shouldBe null
		parseLivePreview(500, """{"previews":true}""") shouldBe null
		parseLivePreview(200, "<html>") shouldBe null
		parseLivePreview(200, null) shouldBe null
	}
})
