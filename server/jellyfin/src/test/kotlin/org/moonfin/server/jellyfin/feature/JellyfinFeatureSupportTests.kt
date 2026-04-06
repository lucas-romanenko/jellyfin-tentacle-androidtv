package org.moonfin.server.jellyfin.feature

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.moonfin.server.core.feature.ServerFeature

class JellyfinFeatureSupportTests : FunSpec({

	test("Jellyfin supports QUICK_CONNECT") {
		JellyfinFeatureSupport.isSupported(ServerFeature.QUICK_CONNECT) shouldBe true
	}

	test("Jellyfin supports SYNC_PLAY") {
		JellyfinFeatureSupport.isSupported(ServerFeature.SYNC_PLAY) shouldBe true
	}

	test("Jellyfin supports MEDIA_SEGMENTS") {
		JellyfinFeatureSupport.isSupported(ServerFeature.MEDIA_SEGMENTS) shouldBe true
	}

	test("Jellyfin supports TRICKPLAY") {
		JellyfinFeatureSupport.isSupported(ServerFeature.TRICKPLAY) shouldBe true
	}

	test("Jellyfin supports LYRICS") {
		JellyfinFeatureSupport.isSupported(ServerFeature.LYRICS) shouldBe true
	}

	test("Jellyfin supports CLIENT_LOG") {
		JellyfinFeatureSupport.isSupported(ServerFeature.CLIENT_LOG) shouldBe true
	}

	test("Jellyfin supports JELLYSEERR") {
		JellyfinFeatureSupport.isSupported(ServerFeature.JELLYSEERR) shouldBe true
	}

	test("Jellyfin does not support WATCH_PARTY") {
		JellyfinFeatureSupport.isSupported(ServerFeature.WATCH_PARTY) shouldBe false
	}

	test("Jellyfin does not support BIF_TRICKPLAY") {
		JellyfinFeatureSupport.isSupported(ServerFeature.BIF_TRICKPLAY) shouldBe false
	}

	test("Jellyfin does not support EMBY_CONNECT") {
		JellyfinFeatureSupport.isSupported(ServerFeature.EMBY_CONNECT) shouldBe false
	}

	test("supportedFeatures matches expected set") {
		JellyfinFeatureSupport.supportedFeatures shouldBe setOf(
			ServerFeature.QUICK_CONNECT,
			ServerFeature.SYNC_PLAY,
			ServerFeature.MEDIA_SEGMENTS,
			ServerFeature.TRICKPLAY,
			ServerFeature.LYRICS,
			ServerFeature.CLIENT_LOG,
			ServerFeature.JELLYSEERR,
		)
	}

	test("Jellyfin and Emby feature sets are disjoint except JELLYSEERR") {
		val embyFeatures = setOf(
			ServerFeature.WATCH_PARTY,
			ServerFeature.BIF_TRICKPLAY,
			ServerFeature.EMBY_CONNECT,
			ServerFeature.JELLYSEERR,
		)
		val overlap = JellyfinFeatureSupport.supportedFeatures.intersect(embyFeatures)
		overlap shouldBe setOf(ServerFeature.JELLYSEERR)
	}
})
