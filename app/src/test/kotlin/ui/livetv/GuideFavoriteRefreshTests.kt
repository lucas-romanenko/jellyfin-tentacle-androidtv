package org.jellyfin.androidtv.ui.livetv

import android.widget.LinearLayout
import android.widget.TextView
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jellyfin.androidtv.ui.GuideChannelHeader
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.util.UUID

private fun guideChannel(n: Int, favorite: Boolean): BaseItemDto {
	val id = UUID.fromString("%08d-0000-4000-8000-000000000000".format(n))
	return BaseItemDto(
		id = id,
		type = BaseItemKind.TV_CHANNEL,
		userData = UserItemDataDto(playbackPositionTicks = 0, playCount = 0, isFavorite = favorite, played = false, key = "$n", itemId = id),
	)
}

private fun setField(target: Any?, owner: Class<*>, name: String, value: Any?) =
	owner.getDeclaredField(name).apply { isAccessible = true }.set(target, value)

/** A guide row header standing for [channel]. */
private fun header(channel: BaseItemDto): GuideChannelHeader = mockk(relaxed = true) {
	every { getChannel() } returns channel
}

/** A guide's channel column holding [children]. */
private fun column(vararg children: Any): LinearLayout = mockk {
	every { childCount } returns children.size
	every { getChildAt(any()) } answers { children[firstArg()] as android.view.View }
}

/** Favouriting a channel from the guide shows its heart on the channel's row. */
class GuideFavoriteRefreshTests : FunSpec({
	val old = guideChannel(1, favorite = false)
	val other = guideChannel(2, favorite = false)
	val favourited = guideChannel(1, favorite = true)

	afterTest { setField(null, TvManager::class.java, "allChannels", null) }

	test("the guide refreshes the favourited channel's row with the new state") {
		// The popup has stored the favourited copy (TvManager.updateChannel) and asks the guide to refresh that channel.
		setField(null, TvManager::class.java, "allChannels", arrayListOf(favourited, other))

		val oldRow = header(old)
		val otherRow = header(other)
		val guide = mockk<LiveTvGuideFragment> {
			every { refreshFavorite(any()) } answers { callOriginal() }
		}
		setField(guide, LiveTvGuideFragment::class.java, "mChannels", column(mockk<TextView>(), oldRow, otherRow))

		guide.refreshFavorite(old.id)

		verify { oldRow.setChannel(favourited) }
		verify { oldRow.refreshFavorite() }
		verify(exactly = 0) { otherRow.refreshFavorite() }
	}

	test("the stored channel list keeps the favourited copy, so the popup opens with it") {
		setField(null, TvManager::class.java, "allChannels", arrayListOf(old, other))
		TvManager.updateChannel(favourited)
		TvManager.getChannel(TvManager.getAllChannelsIndex(old.id)).userData?.isFavorite shouldBe true
		TvManager.getChannel(1) shouldBe other

		// A channel no longer in the list, or no list yet, changes nothing.
		TvManager.updateChannel(guideChannel(3, favorite = true))
		TvManager.getAllChannels().size shouldBe 2
		setField(null, TvManager::class.java, "allChannels", null)
		TvManager.updateChannel(favourited)
	}
})
