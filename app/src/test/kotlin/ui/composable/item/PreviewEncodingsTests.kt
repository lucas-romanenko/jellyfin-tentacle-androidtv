package org.jellyfin.androidtv.ui.composable.item

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import java.util.UUID

/** A card preview's server transcode ends with the preview, and only it (#47). */
class PreviewEncodingsTests : FunSpec({
	test("a preview's stream carries its own play session and the device") {
		val api = createJellyfin {
			context = mockk(relaxed = true)
			clientInfo = ClientInfo(name = "Tentacle test", version = "1")
			deviceInfo = DeviceInfo(id = "tv-device-1", name = "Test TV")
		}.createApi(baseUrl = "http://jellyfin:8096", accessToken = "token")
		val url = previewStreamUrl(api, UUID.fromString("00000000-0000-0000-0000-000000000042"), "session123")
		url shouldContain "playSessionId=session123"
		url shouldContain "deviceId=tv-device-1"
		url shouldContain "videoCodec=h264"
	}

	test("every preview gets a session id of its own") {
		newPreviewSessionId() shouldNotBe newPreviewSessionId()
	}

	test("without a session id nothing is sent: a device id alone would stop real playback") {
		var calls = 0
		PreviewEncodings.stop("tv", null) { _, _ -> calls++ }.shouldBeNull()
		PreviewEncodings.stop("tv", "  ") { _, _ -> calls++ }.shouldBeNull()
		calls shouldBe 0
	}

	test("the request names the device and the preview's session, once per session") {
		val sent = mutableListOf<Pair<String, String>>()
		val session = newPreviewSessionId()
		PreviewEncodings.stop("tv-device-1", session) { d, s -> sent += d to s }.shouldNotBeNull().join()
		PreviewEncodings.stop("tv-device-1", session) { d, s -> sent += d to s }.shouldBeNull()
		sent shouldBe listOf("tv-device-1" to session)
	}

	test("a failed stop is ignored") {
		PreviewEncodings.stop("tv", newPreviewSessionId()) { _, _ -> error("server away") }
			.shouldNotBeNull().join()
	}
})
