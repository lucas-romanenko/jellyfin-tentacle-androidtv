package org.jellyfin.androidtv.ui.livetv

import android.view.View
import android.widget.LinearLayout
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import io.mockk.verify
import org.jellyfin.androidtv.preference.SystemPreferences
import org.jellyfin.androidtv.ui.GuideChannelHeader
import org.jellyfin.androidtv.ui.ObservableHorizontalScrollView
import org.jellyfin.androidtv.ui.ObservableScrollView
import org.jellyfin.androidtv.ui.ProgramGridCell
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.time.LocalDateTime
import java.util.UUID

private fun channel(n: Int, name: String) = BaseItemDto(
	id = UUID.fromString("%08d-0000-4000-8000-000000000000".format(n)),
	name = name,
	type = BaseItemKind.TV_CHANNEL,
)

private fun setField(target: Any?, name: String, value: Any?) =
	LiveTvGuideFragment::class.java.getDeclaredField(name).apply { isAccessible = true }.set(target, value)

/** The guide's rows as laid out after the load: 100 px each, one programme on now in each. */
private class Rows(channels: List<BaseItemDto>) {
	val cells = channels.associate { channel ->
		channel.id to mockk<ProgramGridCell>(relaxed = true) {
			every { program } returns BaseItemDto(
				id = UUID.randomUUID(),
				type = BaseItemKind.PROGRAM,
				channelId = channel.id,
				startDate = LocalDateTime.now().minusMinutes(20),
				endDate = LocalDateTime.now().plusMinutes(40),
			)
		}
	}
	val headers: List<View> = channels.map { c -> mockk<GuideChannelHeader>(relaxed = true) { every { getChannel() } returns c } }
	val rows: List<View> = channels.mapIndexed { i, c ->
		mockk<LinearLayout>(relaxed = true) {
			every { top } returns i * 100
			every { bottom } returns i * 100 + 100
			every { childCount } returns 1
			every { getChildAt(0) } returns cells.getValue(c.id)
		}
	}
	val channelColumn = mockk<LinearLayout>(relaxed = true) {
		every { childCount } returns headers.size
		every { getChildAt(any()) } answers { headers[firstArg()] }
	}
	val programRows = mockk<LinearLayout>(relaxed = true) {
		every { childCount } returns rows.size
		every { getChildAt(any()) } answers { rows[firstArg()] }
		// The guide's posts run as soon as they are asked for (the rows are laid out)
		every { post(any()) } answers { firstArg<Runnable>().run(); true }
	}
	val vScroller = mockk<ObservableScrollView>(relaxed = true) { every { height } returns 600 }
	val hScroller = mockk<ObservableHorizontalScrollView>(relaxed = true)

	/** The channels whose programme was asked to take focus. */
	fun focused(): List<String?> = cells.filter { (_, cell) ->
		runCatching { verify(atLeast = 1) { cell.requestFocus() } }.isSuccess
	}.keys.map { id -> nameOf[id] }

	private val nameOf = channels.associate { it.id to it.name }
}

/**
 * Back from a channel, the guide comes back where it was, with focus on the channel played last
 * (GuidePlace, #161) — also when Channel Up / Down in the player went to a channel this view
 * doesn't show: then focus stays on the channel left, not on the first row (whose focus makes the
 * next key press scroll the guide back to the top).
 */
class GuideBackFromChannelFocusTests : FunSpec({
	// Favorites at the top of All, then the rest: Channel Up from the last favourite plays a channel
	// that only All has
	val favorites = (0 until 23).map { channel(it, if (it == 22) "Last favourite" else "Favourite $it") }
	val nextInAll = channel(23, "First other channel")
	val guideStart = LocalDateTime.now().withSecond(0).withNano(0).let { it.withMinute(if (it.minute >= 30) 30 else 0) }

	fun guideBack(view: GuideView, shown: List<BaseItemDto>, lastPlayed: BaseItemDto): Rows {
		val rows = Rows(shown)
		// The guide's own code from here, attached: where it puts the rows and the focus back
		startKoin { modules(module { single { mockk<SystemPreferences>(relaxed = true) } }) }
		val guide = try {
			spyk(LiveTvGuideFragment(), recordPrivateCalls = true) { every { isAdded } returns true }
		} finally {
			stopKoin()
		}
		// Left with "Last favourite" focused, rows 17 and down on screen (another favourite had been played before)
		setField(guide, "mPlace", GuidePlace(
			view, guideStart, shown.first().id, shown[17].id, 20, guideStart, true, favorites[22].id, favorites[16].id,
		))
		setField(guide, "mView", view)
		setField(guide, "mAllChannels", shown)
		setField(guide, "mCurrentGuideStart", guideStart)
		setField(guide, "guideRowHeightPx", 100)
		setField(guide, "guideRowWidthPerMinPx", 7)
		setField(guide, "mChannels", rows.channelColumn)
		setField(guide, "mProgramRows", rows.programRows)
		setField(guide, "mProgramVScroller", rows.vScroller)
		setField(guide, "mProgramHScroller", rows.hScroller)

		mockkStatic(TvManager::class)
		try {
			every { TvManager.getLastLiveTvChannel() } returns lastPlayed.id
			LiveTvGuideFragment::class.java.getDeclaredMethod("restorePlace").apply { isAccessible = true }.invoke(guide)
		} finally {
			unmockkStatic(TvManager::class)
		}
		return rows
	}

	test("Channel Up to a channel the view shows: focus on it, the rows where they were") {
		val all = favorites + nextInAll
		val rows = guideBack(GuideView.ALL, all, lastPlayed = nextInAll)

		rows.focused() shouldBe listOf("First other channel")
		// the rows as left, one row further to show it
		verify { rows.vScroller.scrollTo(0, 1800) }
	}

	test("Channel Up past the last favourite: back in Favorites, focus on the channel left, not the first row") {
		val rows = guideBack(GuideView.FAVORITES, favorites, lastPlayed = nextInAll)

		// The rows come back as they were left...
		verify { rows.vScroller.scrollTo(0, 1720) }
		// ...and so must focus: with none asked for, the first row (focused while the rows went up)
		// keeps it, off screen, and the first key press scrolls the guide to the top
		withClue("programmes asked to take focus after Back (the channel played last isn't a favourite)") {
			rows.focused() shouldBe listOf("Last favourite")
		}
	}
})
