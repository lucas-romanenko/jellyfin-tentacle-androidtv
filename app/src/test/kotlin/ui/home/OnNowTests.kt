package org.jellyfin.androidtv.ui.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.LocalDateTime
import java.util.UUID

/** On Now: the favourite channels with something on right now, as cards that tune in. */
class OnNowTests : FunSpec({
	val now = LocalDateTime.of(2026, 10, 21, 20, 30)

	fun channel(name: String, program: String?, start: LocalDateTime?, end: LocalDateTime?) = BaseItemDto(
		id = UUID.randomUUID(),
		type = BaseItemKind.TV_CHANNEL,
		name = name,
		number = "4",
		currentProgram = if (program == null && end == null) null else BaseItemDto(
			id = UUID.randomUUID(),
			type = BaseItemKind.PROGRAM,
			name = program,
			startDate = start,
			endDate = end,
		),
	)

	test("a channel's card is named after the programme, with the channel and time left below") {
		val tsn = channel("TSN 1", "NHL: Leafs at Canadiens", now.minusMinutes(45), now.plusMinutes(19))
		val items = onNowItems(listOf(tsn), now)
		items.size shouldBe 1
		items[0].name shouldBe "NHL: Leafs at Canadiens"
		items[0].number shouldBe "TSN 1 · 19m left"
		items[0].id shouldBe tsn.id
		items[0].type shouldBe BaseItemKind.TV_CHANNEL
	}

	test("channels with nothing on, a finished or a future programme are left out") {
		onNowItems(
			listOf(
				channel("No guide", null, null, null),
				channel("Ended", "Old", now.minusHours(2), now.minusMinutes(1)),
				channel("Later", "Soon", now.plusMinutes(5), now.plusHours(1)),
				channel("No end", "?", now.minusMinutes(5), null),
			),
			now,
		).shouldBeEmpty()
	}

	test("a programme without a title shows the channel's name") {
		onNowItems(listOf(channel("CBC News", "", now.minusMinutes(1), now.plusMinutes(30))), now)[0].name shouldBe "CBC News"
	}

	test("time left reads in minutes, hours past an hour, never 0m") {
		timeLeft(now, now.plusMinutes(19)) shouldBe "19m left"
		timeLeft(now, now.plusMinutes(65)) shouldBe "1h 5m left"
		timeLeft(now, now.plusSeconds(10)) shouldBe "1m left"
	}

	test("at most 30 cards") {
		onNowItems(List(40) { channel("C$it", "P", now.minusMinutes(1), now.plusMinutes(10)) }, now).size shouldBe 30
	}
})
