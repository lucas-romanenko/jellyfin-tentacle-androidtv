package org.jellyfin.androidtv.ui.playback

import androidx.media3.common.PlaybackException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * A server stream that times out before its first frame is prepared again in place, so a retry
 * doesn't start a second encode next to the one the server is still preparing (H1).
 */
class ServerStartWaitTests : FunSpec({
	val timeout = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT

	test("a transcode that times out before its first frame is waited for") {
		canWaitForServerStart(isLive = false, serverStream = true, hasPlayed = false, waits = 0, errorCode = timeout) shouldBe true
		canWaitForServerStart(isLive = false, serverStream = true, hasPlayed = false, waits = 0,
			errorCode = PlaybackException.ERROR_CODE_IO_UNSPECIFIED) shouldBe true
	}

	test("only SERVER_START_WAITS times per item") {
		canWaitForServerStart(isLive = false, serverStream = true, hasPlayed = false, waits = SERVER_START_WAITS - 1, errorCode = timeout) shouldBe true
		canWaitForServerStart(isLive = false, serverStream = true, hasPlayed = false, waits = SERVER_START_WAITS, errorCode = timeout) shouldBe false
	}

	test("direct play, live TV and a stream that already played go to the retry ladder") {
		canWaitForServerStart(isLive = false, serverStream = false, hasPlayed = false, waits = 0, errorCode = timeout) shouldBe false
		canWaitForServerStart(isLive = true, serverStream = true, hasPlayed = false, waits = 0, errorCode = timeout) shouldBe false
		canWaitForServerStart(isLive = false, serverStream = true, hasPlayed = true, waits = 0, errorCode = timeout) shouldBe false
	}

	test("a refusal or a decoder error isn't waited for") {
		canWaitForServerStart(isLive = false, serverStream = true, hasPlayed = false, waits = 0,
			errorCode = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) shouldBe false
		canWaitForServerStart(isLive = false, serverStream = true, hasPlayed = false, waits = 0,
			errorCode = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) shouldBe false
	}

	test("the first wait says what is being prepared, later ones say still") {
		startWaitMessage(waitsBefore = 0, burningSubtitles = true) shouldBe StartWaitMessage.SUBTITLES
		startWaitMessage(waitsBefore = 0, burningSubtitles = false) shouldBe StartWaitMessage.STREAM
		startWaitMessage(waitsBefore = 1, burningSubtitles = true) shouldBe StartWaitMessage.STILL
		startWaitMessage(waitsBefore = 3, burningSubtitles = false) shouldBe StartWaitMessage.STILL
	}
})
