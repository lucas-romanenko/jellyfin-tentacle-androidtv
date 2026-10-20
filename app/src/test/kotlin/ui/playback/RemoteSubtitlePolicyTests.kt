package org.jellyfin.androidtv.ui.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod

/**
 * A remote source never starts with a subtitle the server chose itself and must draw onto the
 * picture: it first read the whole remote file, and the start timed out (H1).
 */
class RemoteSubtitlePolicyTests : FunSpec({
	test("a provider link is remote") {
		isRemoteSource(isRemote = true, protocol = MediaProtocol.FILE) shouldBe true
		isRemoteSource(isRemote = false, protocol = MediaProtocol.HTTP) shouldBe true
		isRemoteSource(isRemote = null, protocol = MediaProtocol.HTTP) shouldBe true
	}

	test("a file on the server is not") {
		isRemoteSource(isRemote = false, protocol = MediaProtocol.FILE) shouldBe false
		isRemoteSource(isRemote = null, protocol = null) shouldBe false
		isRemoteSource(null) shouldBe false
	}

	test("the server's own burn-in choice on a remote source is skipped") {
		skipAutoBurnIn(userPicked = false, remote = true, delivery = SubtitleDeliveryMethod.ENCODE) shouldBe true
	}

	test("a subtitle the user picked is kept") {
		skipAutoBurnIn(userPicked = true, remote = true, delivery = SubtitleDeliveryMethod.ENCODE) shouldBe false
	}

	test("burn-in of a local file is kept") {
		skipAutoBurnIn(userPicked = false, remote = false, delivery = SubtitleDeliveryMethod.ENCODE) shouldBe false
	}

	test("subtitles the player shows itself, and none, are kept") {
		for (delivery in listOf(SubtitleDeliveryMethod.EMBED, SubtitleDeliveryMethod.EXTERNAL, SubtitleDeliveryMethod.HLS, SubtitleDeliveryMethod.DROP, null)) {
			skipAutoBurnIn(userPicked = false, remote = true, delivery = delivery) shouldBe false
		}
	}

	test("no source or no subtitle has no delivery") {
		subtitleDelivery(null, 2) shouldBe null
		subtitleDelivery(null, -1) shouldBe null
		skipAutoBurnIn(userPicked = false, source = null, requestedIndex = null) shouldBe false
	}
})
