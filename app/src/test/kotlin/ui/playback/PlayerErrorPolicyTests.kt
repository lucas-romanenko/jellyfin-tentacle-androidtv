package org.jellyfin.androidtv.ui.playback

import androidx.media3.common.PlaybackException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Live TV retries in place before reopening (#19); a refused remote source says why (#44). */
class PlayerErrorPolicyTests : FunSpec({
	test("network errors and falling behind the live window are retried in place") {
		canReprepareLiveInPlace(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, null) shouldBe true
		canReprepareLiveInPlace(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null) shouldBe true
		canReprepareLiveInPlace(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, null) shouldBe true
		canReprepareLiveInPlace(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW, null) shouldBe true
	}

	test("a server error on a segment is retried in place, a refusal is not") {
		canReprepareLiveInPlace(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 500) shouldBe true
		canReprepareLiveInPlace(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 503) shouldBe true
		canReprepareLiveInPlace(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 404) shouldBe false
		canReprepareLiveInPlace(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 401) shouldBe false
	}

	test("decoder and format errors go to the retry ladder") {
		canReprepareLiveInPlace(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, null) shouldBe false
		canReprepareLiveInPlace(PlaybackException.ERROR_CODE_DECODING_FAILED, null) shouldBe false
		canReprepareLiveInPlace(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, null) shouldBe false
		canReprepareLiveInPlace(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, null) shouldBe false
	}

	test("the server's detail is read from a JSON error body") {
		serverErrorDetail("""{"detail": "Some Channel is not streaming right now"}""".toByteArray()) shouldBe
			"Some Channel is not streaming right now"
	}

	test("anything else has no detail") {
		serverErrorDetail(null) shouldBe null
		serverErrorDetail(ByteArray(0)) shouldBe null
		serverErrorDetail("Error processing request.".toByteArray()) shouldBe null
		serverErrorDetail("<html><body>502 Bad Gateway</body></html>".toByteArray()) shouldBe null
		serverErrorDetail("""{"error": "x"}""".toByteArray()) shouldBe null
		serverErrorDetail("""{"detail": ""}""".toByteArray()) shouldBe null
		serverErrorDetail("""{"detail": {"loc": ["body"]}}""".toByteArray()) shouldBe null
	}
})
