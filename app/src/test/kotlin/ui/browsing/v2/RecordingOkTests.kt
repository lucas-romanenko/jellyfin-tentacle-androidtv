package org.jellyfin.androidtv.ui.browsing.v2

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime
import java.util.UUID

class RecordingOkTests : FunSpec({
	val since = LocalDateTime.of(2026, 10, 24, 19, 55)

	test("recording now: OK asks how to watch it (#239)") {
		recordingOk(RecordingNowInfo(since, "TSN 2", UUID.randomUUID())) shouldBe RecordingOk.CHOOSE
		recordingOk(RecordingNowInfo(since, null, null)) shouldBe RecordingOk.CHOOSE
	}

	test("a finished recording opens as before") {
		recordingOk(null) shouldBe RecordingOk.OPEN
	}

	test("the choices: Watch live first, then the saved position, then the start (one player, #239)") {
		recordingNowChoices(resumeTicks = null) shouldBe
			listOf(RecordingNowChoice.WATCH_LIVE, RecordingNowChoice.FROM_START)
		recordingNowChoices(resumeTicks = 0) shouldBe
			listOf(RecordingNowChoice.WATCH_LIVE, RecordingNowChoice.FROM_START)
		recordingNowChoices(resumeTicks = 12_000_000_000) shouldBe
			listOf(RecordingNowChoice.WATCH_LIVE, RecordingNowChoice.RESUME, RecordingNowChoice.FROM_START)
	}
})
