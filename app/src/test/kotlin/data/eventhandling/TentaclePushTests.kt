package org.jellyfin.androidtv.data.eventhandling

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Tentacle's push rides on RefreshProgress: only its own ones count. */
class TentaclePushTests : FunSpec({
	test("Tentacle's push gives the topics that changed") {
		tentaclePushTopics(mapOf("ItemId" to "tentacle", "Progress" to "0", "Tentacle" to "requests,activity,notifications")) shouldBe
			setOf("requests", "activity", "notifications")
		tentaclePushTopics(mapOf("ItemId" to "tentacle", "Progress" to "0", "Tentacle" to " notifications ,")) shouldBe
			setOf("notifications")
	}

	test("Tentacle's \"status\" push (its plain-words status changed) is a topic like the others") {
		tentaclePushTopics(mapOf("ItemId" to "tentacle", "Progress" to "0", "Tentacle" to "recordings,Status")) shouldBe
			setOf("recordings", "status")
		("status" in tentaclePushTopics(mapOf("ItemId" to "tentacle", "Tentacle" to "status")).orEmpty()) shouldBe true
	}

	test("a push without topics changes nothing") {
		tentaclePushTopics(mapOf("ItemId" to "tentacle", "Progress" to "0")) shouldBe emptySet()
	}

	test("Jellyfin's own RefreshProgress (a library scan) is not a push") {
		tentaclePushTopics(mapOf("ItemId" to "0f3c2a9b8d7e4f5a9b1c2d3e4f5a6b7c", "Progress" to "42.5")) shouldBe null
		tentaclePushTopics(emptyMap()) shouldBe null
		tentaclePushTopics(null) shouldBe null
	}
})
