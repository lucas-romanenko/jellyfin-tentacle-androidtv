package org.jellyfin.androidtv.ui.livetv

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.time.LocalDateTime
import java.util.UUID

private val t0: LocalDateTime = LocalDateTime.of(2026, 10, 25, 20, 0)

private fun id(n: Int): UUID = UUID.fromString("%08d-0000-4000-8000-000000000000".format(n))

private fun channel(n: Int, playedMinutesAgo: Long? = null, program: Int? = null): BaseItemDto = BaseItemDto(
	id = id(n),
	type = BaseItemKind.TV_CHANNEL,
	name = "Channel $n",
	userData = UserItemDataDto(
		playbackPositionTicks = 0,
		playCount = if (playedMinutesAgo != null) 1 else 0,
		isFavorite = false,
		played = false,
		key = "$n",
		itemId = id(n),
		lastPlayedDate = playedMinutesAgo?.let { t0.minusMinutes(it) },
	),
	currentProgram = program?.let { BaseItemDto(id = id(1000 + it), type = BaseItemKind.PROGRAM) },
)

/** The player's channel row: the person's recent channels, most recent first (jellyfin-tentacle-androidtv: live player). */
class RecentChannelsTests : FunSpec({
	test("most recent first, the channel playing left out, never-played channels left out") {
		val server = listOf(channel(1, 0), channel(2, 5), channel(3, 60), channel(4, null))
		recentChannelOrder(server, emptyMap(), current = id(1)).map { it.id } shouldBe listOf(id(2), id(3))
	}

	test("a channel tuned here a moment ago counts before the server knows") {
		// The server still has 3 older than 2; this TV just left 3 for 1
		val server = listOf(channel(2, 5), channel(3, 60), channel(1, null))
		val local = mapOf(id(3) to t0.minusMinutes(1), id(1) to t0)
		recentChannelOrder(server, local, current = id(1)).map { it.id } shouldBe listOf(id(3), id(2))
	}

	test("the server's later play wins over an older one here (another device)") {
		val server = listOf(channel(2, 1), channel(3, 30))
		val local = mapOf(id(2) to t0.minusMinutes(90))
		recentChannelOrder(server, local, current = null).map { it.id } shouldBe listOf(id(2), id(3))
	}

	test("at most the limit, and a channel only once") {
		val server = (1..15).map { channel(it, it.toLong()) } + channel(1, 1)
		val order = recentChannelOrder(server, emptyMap(), current = null, limit = 10)
		order.size shouldBe 10
		order.map { it.id }.distinct().size shouldBe 10
		order.first().id shouldBe id(1)
	}

	test("no recents: the favourites, without the one playing") {
		val favorites = listOf(channel(5), channel(6), channel(7))
		val list = recentOrFavorites(emptyList(), favorites, current = id(6))
		list.favorites shouldBe true
		list.channels.map { it.id } shouldBe listOf(id(5), id(7))
	}

	test("recents shown when there are any") {
		val list = recentOrFavorites(listOf(channel(2, 1)), listOf(channel(5)), current = null)
		list.favorites shouldBe false
		list.channels.map { it.id } shouldBe listOf(id(2))
	}

	test("the row changes only when the channels, their order or what's on differ") {
		val a = RecentChannelsList(listOf(channel(1, 1, program = 1), channel(2, 2, program = 2)), favorites = false)
		recentChannelsChanged(null, a) shouldBe true
		recentChannelsChanged(a, a.copy(channels = a.channels.map { it.copy() })) shouldBe false
		recentChannelsChanged(a, a.copy(channels = a.channels.reversed())) shouldBe true
		recentChannelsChanged(a, a.copy(channels = listOf(channel(1, 1, program = 9), channel(2, 2, program = 2)))) shouldBe true
		recentChannelsChanged(a, a.copy(favorites = true)) shouldBe true
	}
})
