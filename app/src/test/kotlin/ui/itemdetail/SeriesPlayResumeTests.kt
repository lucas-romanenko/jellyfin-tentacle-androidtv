package org.jellyfin.androidtv.ui.itemdetail.v2

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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

	/** What Play on [item] starts, and where (ms), as handlePlay plays it. */
	fun pressPlay(item: BaseItemDto, uiState: ItemDetailsUiState): Pair<BaseItemDto, Int> {
		val episode = playEpisode(item, uiState)!!
		return episode to resumeStartMs(episode, prerollSeconds = 0)
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

	test("a watched season starts again at its first episode") {
		val season = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.SEASON)
		val first = episode(played = true)

		playEpisode(season, ItemDetailsUiState(item = season, episodes = listOf(first, episode(played = true))))?.id shouldBe first.id
	}

	test("a series without Next Up has no loaded episode (Play fetches the first one)") {
		val series = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.SERIES)

		playEpisode(series, ItemDetailsUiState(item = series)) shouldBe null
	}
})
