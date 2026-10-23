package org.jellyfin.androidtv.ui.itemdetail.v2

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.util.UUID

/**
 * Play on a SEASON page starts the season's first unfinished episode at its resume point, as
 * series Play does (#78). A season page is drawn by SeasonDetailsContent, whose Play button (the
 * page's first focus) has its own onClick and does not go through handlePlay.
 *
 * The button lives inside a composable, so the test runs the page's real click lambdas (Kotlin
 * compiles them to static methods of the fragment) on the real fragment and records what reaches
 * play(item, positionMs, shuffle), the step into the player.
 */
class SeasonPlayButtonResumeTests : FunSpec({
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

	/** Presses every button of the season page that needs only the page state; returns each play(). */
	fun pressSeasonButtons(season: BaseItemDto, uiState: ItemDetailsUiState): List<Pair<BaseItemDto, Int>> {
		val plays = mutableListOf<Pair<BaseItemDto, Int>>()
		val prefs = mockk<UserPreferences> { every { this@mockk[UserPreferences.resumeSubtractDuration] } returns "0" }
		// The real page: only play() (the step into the player) is replaced.
		val fragment = spyk(ItemDetailsFragment())
		ItemDetailsFragment::class.java.getDeclaredField("userPreferences\$delegate")
			.apply { isAccessible = true }
			.set(fragment, lazyOf(prefs))
		every { fragment["play"](any<BaseItemDto>(), any<Int>(), any<Boolean>()) } answers {
			plays += firstArg<BaseItemDto>() to secondArg<Int>()
			Unit
		}

		val values = mapOf<Class<*>, Any>(
			ItemDetailsUiState::class.java to uiState,
			ItemDetailsFragment::class.java to fragment,
			BaseItemDto::class.java to season,
		)
		ItemDetailsFragment::class.java.declaredMethods
			.filter { it.name.startsWith("SeasonDetailsContent\$lambda") }
			.filter { ItemDetailsUiState::class.java in it.parameterTypes && it.parameterTypes.all { type -> type in values } }
			.forEach { onClick ->
				onClick.isAccessible = true
				onClick.invoke(null, *onClick.parameterTypes.map { values.getValue(it) }.toTypedArray())
			}
		return plays
	}

	test("season page Play resumes the half-watched episode instead of starting it at 0:00") {
		val season = BaseItemDto(id = UUID.randomUUID(), type = BaseItemKind.SEASON)
		val watched = episode(played = true)
		val inProgress = episode(positionTicks = 20 * minuteTicks)
		val fresh = episode()
		val uiState = ItemDetailsUiState(item = season, episodes = listOf(watched, inProgress, fresh))

		val plays = pressSeasonButtons(season, uiState)

		plays shouldHaveSize 1
		val (item, positionMs) = plays.single()
		item.id shouldBe inProgress.id
		// 0 here makes the server store a position under MinResumePct as 0: the 20:00 point is lost.
		positionMs shouldBe 20 * 60_000
	}
})
