package org.jellyfin.androidtv.ui.livetv

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.data.repository.answer
import org.jellyfin.androidtv.data.repository.detailOf
import org.jellyfin.androidtv.ui.livetv.multiview.MultiViewPicks
import org.jellyfin.androidtv.ui.livetv.multiview.TileGrid
import org.jellyfin.androidtv.ui.livetv.multiview.TileGrid.Direction.DOWN
import org.jellyfin.androidtv.ui.livetv.multiview.TileGrid.Direction.LEFT
import org.jellyfin.androidtv.ui.livetv.multiview.TileGrid.Direction.RIGHT
import org.jellyfin.androidtv.ui.livetv.multiview.TileGrid.Direction.UP
import org.jellyfin.androidtv.ui.livetv.multiview.sameId
import org.jellyfin.androidtv.ui.livetv.multiview.startLabel
import org.jellyfin.androidtv.ui.livetv.multiview.tileBox
import org.jellyfin.androidtv.ui.livetv.multiview.withSwapped
import org.jellyfin.androidtv.ui.livetv.multiview.withoutTile

/** Multi-view: picking in the guide, moving between tiles, changing them, the server's answers. */
class MultiViewTests : FunSpec({
	context("picking channels in the guide") {
		test("ticked in order, numbered 1-4; OK again unticks and the rest move up") {
			val p = MultiViewPicks()
			p.toggle("a", "TSN") shouldBe MultiViewPicks.Toggle.ADDED
			p.toggle("b", "CBC")
			p.toggle("c", "CTV")
			p.numberOf("b") shouldBe 2
			p.toggle("a") shouldBe MultiViewPicks.Toggle.REMOVED
			p.ids shouldContainExactly listOf("b", "c")
			p.numberOf("b") shouldBe 1
			p.numberOf("a") shouldBe 0
			p.nameOf("c") shouldBe "CTV"
		}

		test("no more than the most (4, or what the free connections allow)") {
			val p = MultiViewPicks(max = 3)
			listOf("a", "b", "c").forEach { p.toggle(it) }
			p.toggle("d") shouldBe MultiViewPicks.Toggle.FULL
			p.ids shouldContainExactly listOf("a", "b", "c")
		}

		test("Start needs two") {
			val p = MultiViewPicks()
			p.toggle("a")
			p.canStart shouldBe false
			p.toggle("b")
			p.canStart shouldBe true
		}

		test("the button says how many and what is next") {
			startLabel(0, 4) shouldBe "0 of 4 · pick 2 more"
			startLabel(1, 4) shouldBe "1 of 4 · pick 1 more"
			startLabel(2, 4) shouldBe "2 of 4 · Start"
			startLabel(3, 3) shouldBe "3 of 3 · Start"
		}
	}

	context("the highlight on the D-pad") {
		test("two side by side") {
			TileGrid.move(0, 2, RIGHT) shouldBe 1
			TileGrid.move(1, 2, LEFT) shouldBe 0
			TileGrid.move(0, 2, LEFT) shouldBe 0
			TileGrid.move(1, 2, DOWN) shouldBe 1
			TileGrid.move(0, 2, UP) shouldBe 0
		}

		test("four in a grid") {
			TileGrid.move(0, 4, DOWN) shouldBe 2
			TileGrid.move(2, 4, RIGHT) shouldBe 3
			TileGrid.move(3, 4, UP) shouldBe 1
			TileGrid.move(1, 4, LEFT) shouldBe 0
			TileGrid.move(3, 4, DOWN) shouldBe 3
		}

		test("three: the empty place is skipped") {
			TileGrid.move(2, 3, RIGHT) shouldBe 2
			TileGrid.move(1, 3, DOWN) shouldBe 2
			TileGrid.move(0, 3, DOWN) shouldBe 2
			TileGrid.move(2, 3, UP) shouldBe 0
		}
	}

	context("changing the tiles") {
		val ids = listOf("a", "b", "c")

		test("remove keeps the order, but never below two") {
			withoutTile(ids, 1) shouldContainExactly listOf("a", "c")
			withoutTile(listOf("a", "b"), 0).shouldBeNull()
		}

		test("swap keeps the place; a channel already there isn't added twice") {
			withSwapped(ids, 2, "d") shouldContainExactly listOf("a", "b", "d")
			withSwapped(ids, 2, "A").shouldBeNull()
		}

		test("ids with and without dashes are the same channel") {
			sameId("0a1b2c3d-0000-0000-0000-000000000001", "0A1B2C3D000000000000000000000001") shouldBe true
			sameId("0a1b", "0a1c") shouldBe false
		}
	}

	test("a tile's place on screen from the server's 1920x1080 layout") {
		val box = tileBox(960, 540, 960, 540)
		box.left shouldBe 0.5f
		box.top shouldBe 0.5f
		box.right shouldBe 1f
		box.bottom shouldBe 1f
		tileBox(0, 270, 960, 540).top shouldBe 0.25f
	}

	context("the server's answers") {
		test("a session") {
			val a = answer(200, """{"id":"abcDEF_123-xyz456789","width":1920,"height":1080,"encoder":"vaapi",
				"tiles":[{"index":0,"audio_track":0,"item_id":"aa","name":"TSN","has_audio":true,"live":true,"x":0,"y":0,"width":960,"height":540},
				{"index":1,"audio_track":1,"item_id":"bb","name":"CBC","x":960,"y":0,"width":960,"height":540,"extra":1}]}""")
			a.session!!.id shouldBe "abcDEF_123-xyz456789"
			a.session!!.tiles.map { it.name } shouldContainExactly listOf("TSN", "CBC")
			a.session!!.tiles[1].audioTrack shouldBe 1
			a.error.shouldBeNull()
		}

		test("refused: the server's own words") {
			val a = answer(409, """{"detail":"Multi-view needs 4 free connections; 2 are free right now."}""")
			a.session.shouldBeNull()
			a.status shouldBe 409
			a.error shouldBe "Multi-view needs 4 free connections; 2 are free right now."
		}

		test("anything else has no reason to show") {
			detailOf("<html>bad gateway</html>").shouldBeNull()
			detailOf("""{"detail":""}""").shouldBeNull()
			detailOf("""{"detail":[{"loc":["body"]}]}""").shouldBeNull()
			answer(502, "").error.shouldBeNull()
		}
	}
})
