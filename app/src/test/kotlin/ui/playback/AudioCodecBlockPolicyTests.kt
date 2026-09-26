package org.jellyfin.androidtv.ui.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * A stuck player blames the audio decoder only when the decoder was fed and did nothing (#51).
 * A single live-TV stall used to block AAC on the device for good.
 */
class AudioCodecBlockPolicyTests : FunSpec({
	val fed = 10_000L

	test("a hardware codec on a local file that stalls with data buffered is blocked") {
		AudioCodecBlockPolicy.shouldBlockCodec("eac3", transcoding = false, remote = false, isLive = false, bufferAheadMs = fed) shouldBe true
		AudioCodecBlockPolicy.shouldBlockCodec("EAC3", transcoding = false, remote = false, isLive = false, bufferAheadMs = fed) shouldBe true
	}

	test("a software-decoded codec is never blocked") {
		for (codec in listOf("aac", "mp3", "opus", "vorbis", "flac")) {
			AudioCodecBlockPolicy.shouldBlockCodec(codec, transcoding = false, remote = false, isLive = false, bufferAheadMs = fed) shouldBe false
		}
	}

	test("live TV, a transcode or a remote stream is never the decoder's fault") {
		AudioCodecBlockPolicy.shouldBlockCodec("eac3", transcoding = false, remote = false, isLive = true, bufferAheadMs = fed) shouldBe false
		AudioCodecBlockPolicy.shouldBlockCodec("eac3", transcoding = true, remote = false, isLive = false, bufferAheadMs = fed) shouldBe false
		AudioCodecBlockPolicy.shouldBlockCodec("eac3", transcoding = false, remote = true, isLive = false, bufferAheadMs = fed) shouldBe false
	}

	test("a stall with nothing buffered ahead is the input stopping") {
		AudioCodecBlockPolicy.shouldBlockCodec("eac3", transcoding = false, remote = false, isLive = false, bufferAheadMs = 0) shouldBe false
		AudioCodecBlockPolicy.shouldBlockCodec("eac3", transcoding = false, remote = false, isLive = false, bufferAheadMs = 2_999) shouldBe false
	}

	test("no codec, nothing to block") {
		AudioCodecBlockPolicy.shouldBlockCodec(null, transcoding = false, remote = false, isLive = false, bufferAheadMs = fed) shouldBe false
		AudioCodecBlockPolicy.shouldBlockCodec(" ", transcoding = false, remote = false, isLive = false, bufferAheadMs = fed) shouldBe false
	}
})
