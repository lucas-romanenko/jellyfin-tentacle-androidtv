package org.moonfin.server.core.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ServerTypeTests : FunSpec({

	test("detect Jellyfin by product name") {
		ServerType.detect("Jellyfin Server", "10.10.0") shouldBe ServerType.JELLYFIN
	}

	test("detect Jellyfin by product name case-insensitive") {
		ServerType.detect("jellyfin", "10.10.0") shouldBe ServerType.JELLYFIN
	}

	test("detect Emby by product name") {
		ServerType.detect("Emby Server", "4.9.0.0") shouldBe ServerType.EMBY
	}

	test("detect Emby by product name case-insensitive") {
		ServerType.detect("emby", "4.9.0.0") shouldBe ServerType.EMBY
	}

	test("detect Emby by 4-part version with low major") {
		ServerType.detect(null, "4.9.0.0") shouldBe ServerType.EMBY
		ServerType.detect(null, "4.8.0.0") shouldBe ServerType.EMBY
		ServerType.detect(null, "3.6.0.0") shouldBe ServerType.EMBY
	}

	test("detect Jellyfin by 3-part version") {
		ServerType.detect(null, "10.10.0") shouldBe ServerType.JELLYFIN
	}

	test("detect Jellyfin by high major version 4-part") {
		ServerType.detect(null, "10.10.0.0") shouldBe ServerType.JELLYFIN
	}

	test("null product name and null version defaults to Jellyfin") {
		ServerType.detect(null, null) shouldBe ServerType.JELLYFIN
	}

	test("null product name and empty version defaults to Jellyfin") {
		ServerType.detect(null, "") shouldBe ServerType.JELLYFIN
	}

	test("unrecognized product name falls back to version heuristic") {
		ServerType.detect("SomeServer", "4.9.0.0") shouldBe ServerType.EMBY
		ServerType.detect("SomeServer", "10.10.0") shouldBe ServerType.JELLYFIN
	}

	test("product name takes priority over version") {
		ServerType.detect("Jellyfin Server", "4.9.0.0") shouldBe ServerType.JELLYFIN
		ServerType.detect("Emby Server", "10.10.0") shouldBe ServerType.EMBY
	}
})
