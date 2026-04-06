package org.moonfin.server.emby.feature

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.moonfin.server.core.feature.ServerFeature

class EmbyFeatureSupportTests : FunSpec({

	test("Emby supports WATCH_PARTY") {
		EmbyFeatureSupport.isSupported(ServerFeature.WATCH_PARTY) shouldBe true
	}

	test("Emby supports BIF_TRICKPLAY") {
		EmbyFeatureSupport.isSupported(ServerFeature.BIF_TRICKPLAY) shouldBe true
	}

	test("Emby supports EMBY_CONNECT") {
		EmbyFeatureSupport.isSupported(ServerFeature.EMBY_CONNECT) shouldBe true
	}

	test("Emby supports JELLYSEERR") {
		EmbyFeatureSupport.isSupported(ServerFeature.JELLYSEERR) shouldBe true
	}

	test("Emby does not support QUICK_CONNECT") {
		EmbyFeatureSupport.isSupported(ServerFeature.QUICK_CONNECT) shouldBe false
	}

	test("Emby does not support SYNC_PLAY") {
		EmbyFeatureSupport.isSupported(ServerFeature.SYNC_PLAY) shouldBe false
	}

	test("Emby does not support MEDIA_SEGMENTS") {
		EmbyFeatureSupport.isSupported(ServerFeature.MEDIA_SEGMENTS) shouldBe false
	}

	test("Emby does not support TRICKPLAY") {
		EmbyFeatureSupport.isSupported(ServerFeature.TRICKPLAY) shouldBe false
	}

	test("Emby does not support LYRICS") {
		EmbyFeatureSupport.isSupported(ServerFeature.LYRICS) shouldBe false
	}

	test("Emby does not support CLIENT_LOG") {
		EmbyFeatureSupport.isSupported(ServerFeature.CLIENT_LOG) shouldBe false
	}

	test("supportedFeatures matches expected set") {
		EmbyFeatureSupport.supportedFeatures shouldBe setOf(
			ServerFeature.WATCH_PARTY,
			ServerFeature.BIF_TRICKPLAY,
			ServerFeature.EMBY_CONNECT,
			ServerFeature.JELLYSEERR,
		)
	}
})
