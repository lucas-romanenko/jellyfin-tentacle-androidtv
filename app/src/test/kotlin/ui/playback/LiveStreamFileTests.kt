package org.jellyfin.androidtv.ui.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Live TV starts from the tuner's own stream instead of the server's HLS remux. */
class LiveStreamFileTests : FunSpec({
	test("an opened live MPEG-TS stream plays as it is") {
		playsLiveStreamFile(infinite = true, liveStreamId = "abc_def", container = "ts") shouldBe true
		playsLiveStreamFile(infinite = true, liveStreamId = "abc_def", container = "MPEGTS") shouldBe true
	}

	test("a stream the server hasn't opened goes through the remux") {
		playsLiveStreamFile(infinite = true, liveStreamId = null, container = "ts") shouldBe false
		playsLiveStreamFile(infinite = true, liveStreamId = "", container = "ts") shouldBe false
	}

	test("files and other containers are left alone") {
		playsLiveStreamFile(infinite = false, liveStreamId = "abc_def", container = "ts") shouldBe false
		playsLiveStreamFile(infinite = null, liveStreamId = "abc_def", container = "ts") shouldBe false
		playsLiveStreamFile(infinite = true, liveStreamId = "abc_def", container = "hls") shouldBe false
		playsLiveStreamFile(infinite = true, liveStreamId = "abc_def", container = null) shouldBe false
	}
})
