package org.jellyfin.androidtv.ui.browsing.v2

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime
import java.util.UUID

class RecordingOkTests : FunSpec({
	val since = LocalDateTime.of(2026, 10, 24, 19, 55)

	test("recording now, channel known: OK watches live (joins the recording's own stream)") {
		recordingOk(RecordingNowInfo(since, "TSN 2", UUID.randomUUID())) shouldBe RecordingOk.WATCH_LIVE
	}

	test("recording now, channel unknown: opens as before (Jellyfin won't stream the growing file)") {
		recordingOk(RecordingNowInfo(since, null, null)) shouldBe RecordingOk.OPEN
	}

	test("a finished recording opens as before") {
		recordingOk(null) shouldBe RecordingOk.OPEN
	}
})
