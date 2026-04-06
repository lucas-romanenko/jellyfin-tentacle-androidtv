package org.moonfin.server.core.feature

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ServerFeatureTests : FunSpec({

	test("ServerFeature enum contains all expected entries") {
		val expected = setOf(
			"QUICK_CONNECT", "SYNC_PLAY", "WATCH_PARTY", "MEDIA_SEGMENTS",
			"TRICKPLAY", "BIF_TRICKPLAY", "LYRICS", "CLIENT_LOG",
			"EMBY_CONNECT", "JELLYSEERR",
		)
		ServerFeature.entries.map { it.name }.toSet() shouldBe expected
	}

	test("ServerFeatureSupport default isSupported delegates to set membership") {
		val support = object : ServerFeatureSupport {
			override val supportedFeatures = setOf(ServerFeature.TRICKPLAY, ServerFeature.LYRICS)
		}

		support.isSupported(ServerFeature.TRICKPLAY) shouldBe true
		support.isSupported(ServerFeature.LYRICS) shouldBe true
		support.isSupported(ServerFeature.SYNC_PLAY) shouldBe false
		support.isSupported(ServerFeature.QUICK_CONNECT) shouldBe false
	}

	test("empty supportedFeatures rejects all features") {
		val support = object : ServerFeatureSupport {
			override val supportedFeatures = emptySet<ServerFeature>()
		}

		ServerFeature.entries.forEach { feature ->
			support.isSupported(feature) shouldBe false
		}
	}

	test("all features supported accepts everything") {
		val support = object : ServerFeatureSupport {
			override val supportedFeatures = ServerFeature.entries.toSet()
		}

		ServerFeature.entries.forEach { feature ->
			support.isSupported(feature) shouldBe true
		}
	}
})
