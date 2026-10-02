package org.jellyfin.androidtv.ui.playback

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.jellyfin.androidtv.databinding.OverlayTvGuideBinding
import org.jellyfin.androidtv.ui.GuideChannelHeader
import org.jellyfin.androidtv.ui.livetv.LiveTvGuide
import org.jellyfin.androidtv.ui.livetv.TvManager
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.util.UUID

private fun channel(n: Int, favorite: Boolean = false): BaseItemDto {
	val id = UUID.fromString("%08d-0000-4000-8000-000000000000".format(n))
	return BaseItemDto(
		id = id,
		type = BaseItemKind.TV_CHANNEL,
		userData = UserItemDataDto(playbackPositionTicks = 0, playCount = 0, isFavorite = favorite, played = false, key = "$n", itemId = id),
	)
}

private fun field(owner: Class<*>, name: String) = owner.getDeclaredField(name).apply { isAccessible = true }

/** The guide shown over live TV playback: favourites and the playing channel's row. */
class PlayerGuideChannelTests : FunSpec({
	afterTest { field(TvManager::class.java, "allChannels").set(null, null) }

	test("favouriting a channel with paging rows in the player's guide refreshes its row instead of crashing") {
		val old = channel(1)
		val other = channel(2)
		val favourited = channel(1, favorite = true)
		// The popup has stored the favourited copy (TvManager.updateChannel) and asks the guide to refresh that channel.
		field(TvManager::class.java, "allChannels").set(null, arrayListOf(favourited, other))

		val oldRow = mockk<GuideChannelHeader>(relaxed = true) { every { getChannel() } returns old }
		val otherRow = mockk<GuideChannelHeader>(relaxed = true) { every { getChannel() } returns other }
		// With more channels than one page, a plain view holds the place of the "next channels" button.
		val children = listOf<View>(oldRow, otherRow, mockk<TextView>())
		val column = mockk<LinearLayout> {
			every { childCount } returns children.size
			every { getChildAt(any()) } answers { children[firstArg()] }
		}
		val binding = mockk<OverlayTvGuideBinding>()
		field(OverlayTvGuideBinding::class.java, "channels").set(binding, column)
		val fragment = mockk<CustomPlaybackOverlayFragment> {
			every { refreshFavorite(any()) } answers { callOriginal() }
		}
		field(CustomPlaybackOverlayFragment::class.java, "tvGuideBinding").set(fragment, binding)

		fragment.refreshFavorite(old.id)

		verify { oldRow.setChannel(favourited) }
		verify { oldRow.refreshFavorite() }
		verify(exactly = 0) { otherRow.refreshFavorite() }
	}

	/** Runs the player guide's row building for [lineup] while [playing] plays; returns the focused row and the row count. */
	fun buildRows(lineup: List<BaseItemDto>, playing: UUID): Pair<Any?, Int> {
		field(TvManager::class.java, "allChannels").set(null, ArrayList(lineup))
		val rows = lineup.associate { it.id to mockk<LinearLayout>(relaxed = true) }
		val fragment = mockk<CustomPlaybackOverlayFragment>(relaxed = true)
		every { fragment["getProgramRow"](any<List<BaseItemDto>>(), any<UUID>()) } answers { rows.getValue(secondArg()) }
		field(CustomPlaybackOverlayFragment::class.java, "mFirstFocusChannelId").set(fragment, playing)

		val task = spyk(fragment.DisplayProgramsTask(mockk<LiveTvGuide>()))
		every { task.isCancelled } returns false
		val taskClass = CustomPlaybackOverlayFragment.DisplayProgramsTask::class.java
		taskClass.getDeclaredMethod("doInBackground", Array<Int>::class.java).apply { isAccessible = true }
			.invoke(task, arrayOf(0, lineup.size - 1))

		val focused = field(taskClass, "firstRow").get(task)
		return rows.entries.firstOrNull { it.value === focused }?.key to field(taskClass, "displayedChannels").getInt(task)
	}

	test("the player's guide puts focus on the playing channel's row and builds the rows after it") {
		val lineup = (1..5).map { channel(it) }
		buildRows(lineup, playing = lineup[2].id) shouldBe (lineup[2].id to 5)
	}

	test("the playing channel first or last on the page, or not on it, never breaks the rows") {
		val lineup = (1..5).map { channel(it) }
		buildRows(lineup, playing = lineup[0].id) shouldBe (lineup[0].id to 5)
		buildRows(lineup, playing = lineup[4].id) shouldBe (lineup[4].id to 5)
		// Not on this page: focus stays on the first row, as before.
		buildRows(lineup, playing = channel(99).id) shouldBe (lineup[0].id to 5)
	}
})
