package org.jellyfin.androidtv.ui.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The player is told a stream is HLS only when it really is (#20). */
class HlsStreamTests : FunSpec({
	test("an HLS container is HLS whatever the URL") {
		isHlsStream("hls", "http://server/resolve/abc") shouldBe true
		isHlsStream("M3U8", "http://server/resolve/abc?x=1") shouldBe true
	}

	test("a .m3u8 path is HLS") {
		isHlsStream("ts", "http://server/videos/1/master.m3u8?api_key=k") shouldBe true
		isHlsStream(null, "http://server/live/index.M3U8") shouldBe true
	}

	test("plain files are not, even with .m3u8 in the query") {
		isHlsStream("mkv", "http://server/Videos/1/stream.mkv?static=true") shouldBe false
		isHlsStream("mp4", "http://server/Videos/1/stream.mp4?next=x.m3u8") shouldBe false
		isHlsStream(null, "not a url") shouldBe false
	}
})
