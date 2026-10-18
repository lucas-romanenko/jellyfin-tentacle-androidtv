package org.jellyfin.androidtv.ui.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

private fun id(n: Int) = UUID.fromString("%08d-0000-4000-8000-000000000000".format(n))

private fun channel(n: Int, timerId: String? = null) = BaseItemDto(
	id = id(n),
	type = BaseItemKind.TV_CHANNEL,
	name = "Channel $n",
	currentProgram = BaseItemDto(id = id(100 + n), type = BaseItemKind.PROGRAM, timerId = timerId),
)

/** The Record button in the live TV player reads the playing channel's current programme. */
class PlayerRecordStateTests : FunSpec({
	test("after Record, the playing channel's programme carries the new timer, so the next press offers Cancel (#123)") {
		val items = listOf(channel(1))
		val refreshed = withRefreshedProgram(items, 0, channel(1, timerId = "t1"))
		refreshed[0].currentProgram?.timerId shouldBe "t1"
		refreshed[0].name shouldBe "Channel 1"
	}

	test("after Cancel, the timer is gone from the playing channel's programme") {
		val refreshed = withRefreshedProgram(listOf(channel(1, timerId = "t1")), 0, channel(1))
		refreshed[0].currentProgram?.timerId shouldBe null
	}

	test("a refresh for another channel, or past the end of the playlist, changes nothing") {
		val items = listOf(channel(1), channel(2))
		withRefreshedProgram(items, 0, channel(2, timerId = "t2")) shouldBeSameInstanceAs items
		withRefreshedProgram(items, 5, channel(1, timerId = "t1")) shouldBeSameInstanceAs items
	}
})
