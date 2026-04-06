package org.jellyfin.androidtv.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.auth.model.Server
import org.moonfin.server.core.feature.ServerFeature
import org.moonfin.server.core.model.ServerType
import org.moonfin.server.emby.feature.EmbyFeatureSupport
import org.moonfin.server.jellyfin.feature.JellyfinFeatureSupport
import java.util.UUID

class FeatureSupportTests : FunSpec({

	fun jellyfinServer() = Server(
		id = UUID.randomUUID(),
		name = "Test Jellyfin",
		address = "http://jellyfin.local:8096",
		serverType = ServerType.JELLYFIN,
	)

	fun embyServer() = Server(
		id = UUID.randomUUID(),
		name = "Test Emby",
		address = "http://emby.local:8096",
		serverType = ServerType.EMBY,
	)

	test("ServerType.JELLYFIN maps to JellyfinFeatureSupport") {
		val support = ServerType.JELLYFIN.featureSupport()
		support shouldBe JellyfinFeatureSupport
	}

	test("ServerType.EMBY maps to EmbyFeatureSupport") {
		val support = ServerType.EMBY.featureSupport()
		support shouldBe EmbyFeatureSupport
	}

	test("null Server supports all features (safe default)") {
		val server: Server? = null

		server.supportsFeature(ServerFeature.QUICK_CONNECT) shouldBe true
		server.supportsFeature(ServerFeature.SYNC_PLAY) shouldBe true
		server.supportsFeature(ServerFeature.MEDIA_SEGMENTS) shouldBe true
		server.supportsFeature(ServerFeature.TRICKPLAY) shouldBe true
		server.supportsFeature(ServerFeature.LYRICS) shouldBe true
		server.supportsFeature(ServerFeature.CLIENT_LOG) shouldBe true
		server.supportsFeature(ServerFeature.WATCH_PARTY) shouldBe true
		server.supportsFeature(ServerFeature.BIF_TRICKPLAY) shouldBe true
		server.supportsFeature(ServerFeature.EMBY_CONNECT) shouldBe true
		server.supportsFeature(ServerFeature.JELLYSEERR) shouldBe true
	}

	test("Jellyfin server supports Jellyfin features") {
		val server = jellyfinServer()

		server.supportsFeature(ServerFeature.QUICK_CONNECT) shouldBe true
		server.supportsFeature(ServerFeature.SYNC_PLAY) shouldBe true
		server.supportsFeature(ServerFeature.MEDIA_SEGMENTS) shouldBe true
		server.supportsFeature(ServerFeature.TRICKPLAY) shouldBe true
		server.supportsFeature(ServerFeature.LYRICS) shouldBe true
		server.supportsFeature(ServerFeature.CLIENT_LOG) shouldBe true
		server.supportsFeature(ServerFeature.JELLYSEERR) shouldBe true
	}

	test("Jellyfin server does not support Emby-only features") {
		val server = jellyfinServer()

		server.supportsFeature(ServerFeature.WATCH_PARTY) shouldBe false
		server.supportsFeature(ServerFeature.BIF_TRICKPLAY) shouldBe false
		server.supportsFeature(ServerFeature.EMBY_CONNECT) shouldBe false
	}

	test("Emby server supports Emby features") {
		val server = embyServer()

		server.supportsFeature(ServerFeature.WATCH_PARTY) shouldBe true
		server.supportsFeature(ServerFeature.BIF_TRICKPLAY) shouldBe true
		server.supportsFeature(ServerFeature.EMBY_CONNECT) shouldBe true
		server.supportsFeature(ServerFeature.JELLYSEERR) shouldBe true
	}

	test("Emby server does not support Jellyfin-only features") {
		val server = embyServer()

		server.supportsFeature(ServerFeature.QUICK_CONNECT) shouldBe false
		server.supportsFeature(ServerFeature.SYNC_PLAY) shouldBe false
		server.supportsFeature(ServerFeature.MEDIA_SEGMENTS) shouldBe false
		server.supportsFeature(ServerFeature.TRICKPLAY) shouldBe false
		server.supportsFeature(ServerFeature.LYRICS) shouldBe false
		server.supportsFeature(ServerFeature.CLIENT_LOG) shouldBe false
	}

	test("JELLYSEERR is supported by both server types") {
		jellyfinServer().supportsFeature(ServerFeature.JELLYSEERR) shouldBe true
		embyServer().supportsFeature(ServerFeature.JELLYSEERR) shouldBe true
	}
})
