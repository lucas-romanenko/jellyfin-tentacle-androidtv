package org.jellyfin.androidtv.ui.livetv

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.util.UUID

private fun channelId(n: Int): UUID = UUID.fromString("%08d-0000-4000-8000-000000000000".format(n))

private fun channel(n: Int, favorite: Boolean = false, name: String = "Channel $n", logo: String? = null): BaseItemDto {
	val id = channelId(n)
	return BaseItemDto(
		id = id,
		type = BaseItemKind.TV_CHANNEL,
		name = name,
		number = "$n",
		imageTags = logo?.let { mapOf(ImageType.PRIMARY to it) },
		userData = UserItemDataDto(playbackPositionTicks = 0, playCount = 0, isFavorite = favorite, played = false, key = "$n", itemId = id),
	)
}

/** How Jellyfin writes ids in its websocket messages: no dashes. */
private fun UUID.bare() = toString().replace("-", "")

private val me = UUID.fromString("11111111-2222-3333-4444-555555555555")
private val someoneElse = UUID.fromString("99999999-2222-3333-4444-555555555555")

/** The guide following favourite and lineup changes while it is open (GuideLiveUpdates). */
class GuideLiveModelTests : FunSpec({
	context("UserDataChanged") {
		val favorites = listOf(channel(1, true), channel(2, true), channel(3, true))

		test("unstar removes the channel from Favorites, the others stay in order") {
			val update = guideUserDataUpdate(
				GuideView.FAVORITES, favorites, me.toString(), me.bare(),
				listOf(GuideUserData(channelId(2).bare(), isFavorite = false)), knownChannelIds = null,
			)
			update.channels!!.map { it.id } shouldContainExactly listOf(channelId(1), channelId(3))
			update.flips shouldBe mapOf(channelId(2) to false)
			update.relist shouldBe false
		}

		test("star in All changes only that channel's star, nothing is re-read") {
			val all = listOf(channel(1, true), channel(2), channel(3))
			val update = guideUserDataUpdate(
				GuideView.ALL, all, me.toString(), me.bare(),
				listOf(GuideUserData(channelId(3).bare(), isFavorite = true)), knownChannelIds = null,
			)
			update.channels!!.map { it.isFavoriteChannel() } shouldContainExactly listOf(true, false, true)
			update.relist shouldBe false
		}

		test("star adds to Favorites: a channel not shown re-reads the list (its name, logo and place)") {
			val update = guideUserDataUpdate(
				GuideView.FAVORITES, favorites, me.toString(), me.bare(),
				listOf(GuideUserData(channelId(7).bare(), isFavorite = true)), knownChannelIds = setOf(channelId(7)),
			)
			update.relist shouldBe true
		}

		test("a starred movie isn't a channel: no re-read when the channels are known") {
			val update = guideUserDataUpdate(
				GuideView.FAVORITES, favorites, me.toString(), me.bare(),
				listOf(GuideUserData(channelId(42).bare(), isFavorite = true)), knownChannelIds = setOf(channelId(1), channelId(7)),
			)
			update.relist shouldBe false
			update.channels.shouldBeNull()
		}

		test("another person's change is ignored") {
			val update = guideUserDataUpdate(
				GuideView.FAVORITES, favorites, me.toString(), someoneElse.bare(),
				listOf(GuideUserData(channelId(1).bare(), isFavorite = false)), knownChannelIds = null,
			)
			update.channels.shouldBeNull()
			update.relist shouldBe false
			guideFavoriteFlips(favorites, me.toString(), someoneElse.toString(), listOf(GuideUserData(channelId(1).toString(), false))).shouldBeEmpty()
			isCurrentUser(null, me.toString()) shouldBe false
		}

		test("ids with and without dashes match, in any case") {
			normalizeItemId(channelId(5).toString()) shouldBe normalizeItemId(channelId(5).bare().uppercase())
			isCurrentUser(me.toString(), me.bare().uppercase()) shouldBe true
			guideFavoriteFlips(favorites, me.bare(), me.toString(), listOf(GuideUserData(channelId(1).bare(), false))) shouldBe
				mapOf(channelId(1) to false)
			guideFavoriteFlips(favorites, me.toString(), me.bare(), listOf(GuideUserData(channelId(1).toString(), false))) shouldBe
				mapOf(channelId(1) to false)
		}

		test("only guide channels whose flag differs change; extra items are ignored") {
			val all = listOf(channel(1, true), channel(2))
			val flips = guideFavoriteFlips(
				all, me.toString(), me.bare(),
				listOf(
					GuideUserData(channelId(1).bare(), true), // already a favourite
					GuideUserData(channelId(2).bare(), true),
					GuideUserData(channelId(99).bare(), false), // not a guide channel
				),
			)
			flips shouldBe mapOf(channelId(2) to true)
			guideUserDataUpdate(GuideView.ALL, all, me.toString(), me.bare(), listOf(GuideUserData(channelId(1).bare(), true)), null)
				.channels.shouldBeNull()
		}

		test("a channel unstarred in the guide itself a moment ago goes from Favorites, also without a message") {
			// The guide flipped its own copy already; the server's message then changes no flag
			val shown = listOf(channel(1, true), channel(2, false), channel(3, true))
			favoritesOnly(shown).map { it.id } shouldContainExactly listOf(channelId(1), channelId(3))
			val update = guideUserDataUpdate(
				GuideView.FAVORITES, shown, me.toString(), me.bare(),
				listOf(GuideUserData(channelId(2).bare(), false)), knownChannelIds = null,
			)
			update.channels!!.map { it.id } shouldContainExactly listOf(channelId(1), channelId(3))
		}

		test("a channel without user data gets some when starred") {
			val bare = BaseItemDto(id = channelId(8), type = BaseItemKind.TV_CHANNEL)
			bare.withFavorite(true).isFavoriteChannel() shouldBe true
			withFavorites(listOf(bare), mapOf(channelId(8) to true)).single().userData?.itemId shouldBe channelId(8)
		}
	}

	context("LibraryChanged: the re-read list against the one shown") {
		val shown = listOf(channel(1), channel(2), channel(3))

		test("the same list is no change") {
			diffGuideChannels(shown, listOf(channel(1), channel(2), channel(3))).isEmpty shouldBe true
		}

		test("added and removed channels") {
			val diff = diffGuideChannels(shown, listOf(channel(1), channel(3), channel(4)))
			diff.added shouldContainExactly listOf(channelId(4))
			diff.removed shouldContainExactly listOf(channelId(2))
			diff.reordered shouldBe false
			diff.lineupChanged shouldBe true
		}

		test("reordered channels") {
			val diff = diffGuideChannels(shown, listOf(channel(3), channel(1), channel(2)))
			diff.reordered shouldBe true
			diff.added.shouldBeEmpty()
			diff.removed.shouldBeEmpty()
			diff.lineupChanged shouldBe false
		}

		test("renamed, renumbered, a new logo or star: changed") {
			val diff = diffGuideChannels(
				shown,
				listOf(channel(1, name = "News HD"), channel(2, logo = "abc"), channel(3, favorite = true)),
			)
			diff.changed shouldContainExactly listOf(channelId(1), channelId(2), channelId(3))
			diff.reordered shouldBe false
			diff.lineupChanged shouldBe false
		}

		test("an access change that hides channels removes only those") {
			val diff = diffGuideChannels(shown, listOf(channel(2)))
			diff.removed shouldContainExactly listOf(channelId(1), channelId(3))
			diff.changed.shouldBeEmpty()
			diff.reordered shouldBe false
		}

		test("the Live TV setup marker is read from CollectionFolders") {
			liveTvVersionFrom(listOf("tentacle-home:*:5", "tentacle-livetv:1761080000000")) shouldBe 1761080000000L
			liveTvVersionFrom(listOf("tentacle-home:*:5")).shouldBeNull()
			liveTvVersionFrom(null).shouldBeNull()
		}
	}

	context("the rows shown after a change") {
		val ids = (0 until 10).map(::channelId)

		test("a page in the middle keeps its channels and what came in between") {
			val newIds = ids.toMutableList().apply { add(5, channelId(50)); remove(channelId(4)) }
			// Page 3..6 shown; 4 went, 50 came after it
			guideLiveWindow(ids, 3, 6, ids.subList(3, 7), newIds) shouldBe 3..6
		}

		test("a page that reached the end of the list still does: a channel added at the end shows") {
			val newIds = ids + channelId(10)
			guideLiveWindow(ids, 0, 9, ids, newIds) shouldBe 0..10
			guideLiveWindow(ids, 0, 9, ids, listOf(channelId(77)) + ids) shouldBe 0..10
		}

		test("nothing shown left, or nothing left at all: the page is shown anew") {
			guideLiveWindow(ids, 0, 2, ids.subList(0, 3), ids.subList(5, 10)).shouldBeNull()
			guideLiveWindow(ids, 0, 9, ids, emptyList()).shouldBeNull()
		}

		test("focus on a row that goes moves to the next row, else the one above") {
			val order = ids.subList(0, 4)
			nearestKeptRow(order, channelId(1), setOf(channelId(0), channelId(2), channelId(3))) shouldBe channelId(2)
			nearestKeptRow(order, channelId(3), setOf(channelId(0), channelId(1))) shouldBe channelId(1)
			nearestKeptRow(order, channelId(1), setOf(channelId(0))) shouldBe channelId(0)
			nearestKeptRow(order, channelId(1), emptySet()).shouldBeNull()
		}
	}
})
