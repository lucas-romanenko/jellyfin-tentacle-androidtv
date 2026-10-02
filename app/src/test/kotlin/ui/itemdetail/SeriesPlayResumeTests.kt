package org.jellyfin.androidtv.ui.itemdetail.v2

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.util.UUID

/**
 * Series and season Play start the chosen episode at its own resume point (#78):
 * starting a half-watched episode at 0:00 makes the server erase that point.
 */
class SeriesPlayResumeTests : FunSpec({
	val minuteTicks = 60L * 10_000_000

	fun episode(positionTicks: Long = 0, played: Boolean = false): BaseItemDto {
		val id = UUID.randomUUID()
		return BaseItemDto(
			id = id,
			type = BaseItemKind.EPISODE,
			userData = UserItemDataDto(
				playbackPositionTicks = positionTicks,
				playCount = if (played) 1 else 0,
				isFavorite = false,
				played = played,
				key = id.toString(),
				itemId = id,
			),
		)
	}

	/** Runs the real handlePlay on a mocked fragment; returns what play() got. */
	fun pressPlay(item: BaseItemDto, uiState: ItemDetailsUiState): Pair<BaseItemDto, Int> {
		val fragment = mockk<ItemDetailsFragment>(relaxed = true)
		val prefs = mockk<UserPreferences>()
		every { prefs[UserPreferences.resumeSubtractDuration] } returns "0"
		ItemDetailsFragment::class.java.getDeclaredField("userPreferences\$delegate")
			.apply { isAccessible = true }
			.set(fragment, lazyOf(prefs))
		every { fragment["handlePlay"](any<BaseItemDto>(), any<ItemDetailsUiState>()) } answers { callOriginal() }
		every { fragment["handleResume"](any<BaseItemDto>()) } answers { callOriginal() }
		val played = slot<BaseItemDto>()
		val position = slot<Int>()
		every { fragment["play"](capture(played), capture(position), any<Boolean>()) } returns Unit

		ItemDetailsFragment::class.java
			.getDeclaredMethod("handlePlay", BaseItemDto::class.java, ItemDetailsUiState::class.java)
			.apply { isAccessible = true }
			.invoke(fragment, item, uiState)

		return played.captured to position.captured
	}

	test("series Play resumes the in-progress Next Up episode") {
		val series = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.SERIES)
		val nextUp = episode(positionTicks = 30 * minuteTicks)

		val (item, positionMs) = pressPlay(series, ItemDetailsUiState(item = series, nextUp = listOf(nextUp)))

		item.id shouldBe nextUp.id
		positionMs shouldBe 30 * 60_000
	}

	test("season Play resumes the first unfinished episode") {
		val season = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.SEASON)
		val watched = episode(played = true)
		val inProgress = episode(positionTicks = 20 * minuteTicks)
		val fresh = episode()

		val (item, positionMs) = pressPlay(
			season,
			ItemDetailsUiState(item = season, episodes = listOf(watched, inProgress, fresh)),
		)

		item.id shouldBe inProgress.id
		positionMs shouldBe 20 * 60_000
	}
})
