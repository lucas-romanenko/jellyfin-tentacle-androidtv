package org.jellyfin.androidtv.util.sdk

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** A YouTube channel's videos are queued from the chosen one on (#42). */
class YouTubeChannelQueueTests : FunSpec({
	test("the channel tag is the yt: one") {
		youtubeChannelTag(listOf("youtube", "yt:some-channel")) shouldBe "yt:some-channel"
		youtubeChannelTag(listOf("YT:Other")) shouldBe "YT:Other"
	}

	test("no channel tag") {
		youtubeChannelTag(null) shouldBe null
		youtubeChannelTag(emptyList()) shouldBe null
		youtubeChannelTag(listOf("youtube", "yt:")) shouldBe null
		youtubeChannelTag(listOf("ytmusic")) shouldBe null
	}

	test("the queue starts at the chosen video") {
		queueFrom(listOf("e", "d", "c", "b", "a")) { it == "c" } shouldBe listOf("c", "b", "a")
		queueFrom(listOf("c")) { it == "c" } shouldBe listOf("c")
	}

	test("no queue when the chosen video isn't in the list") {
		queueFrom(listOf("b", "a")) { it == "c" } shouldBe null
	}
})
