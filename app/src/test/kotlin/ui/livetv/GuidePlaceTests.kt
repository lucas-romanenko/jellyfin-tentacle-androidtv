package org.jellyfin.androidtv.ui.livetv

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime
import java.util.UUID

/** Back from a channel, the guide is where it was left (GuidePlace). */
class GuidePlaceTests : FunSpec({
	val ids = (0 until 400).map { UUID(0, it.toLong()) }
	val now = LocalDateTime.of(2026, 10, 22, 14, 40)
	fun place(
		guideStart: LocalDateTime = LocalDateTime.of(2026, 10, 22, 14, 30),
		pageStart: Int? = 263,
		top: Int? = 299,
		leftTime: LocalDateTime = LocalDateTime.of(2026, 10, 22, 16, 30),
		atNow: Boolean = false,
		focus: Int? = 301,
		lastPlayedAtLeave: Int? = 12,
	) = GuidePlace(
		GuideView.ALL, guideStart, pageStart?.let(ids::get), top?.let(ids::get), 20, leftTime, atNow,
		focus?.let(ids::get), lastPlayedAtLeave?.let(ids::get),
	)

	test("the hours page: the one left when it is ahead, else the current half hour's") {
		val ahead = LocalDateTime.of(2026, 10, 23, 0, 0)
		placeGuideStart(place(guideStart = ahead), now) shouldBe ahead
		placeGuideStart(place(guideStart = LocalDateTime.of(2026, 10, 22, 13, 30)), now) shouldBe LocalDateTime.of(2026, 10, 22, 14, 30)
	}

	test("the time at the left edge comes back, from the page shown now") {
		// 2 h ahead of a page that began at 14:30, now shown from 15:00 (half an hour went by)
		placeScrollX(place(), LocalDateTime.of(2026, 10, 22, 15, 0), pxPerMinute = 7) shouldBe 90 * 7
		// at the current time: the current time again
		placeScrollX(place(atNow = true), LocalDateTime.of(2026, 10, 22, 15, 0), pxPerMinute = 7) shouldBe 0
		// a time gone by: the start of the page
		placeScrollX(place(leftTime = now.minusHours(1)), LocalDateTime.of(2026, 10, 22, 15, 0), pxPerMinute = 7) shouldBe 0
		placeAtNow(LocalDateTime.of(2026, 10, 22, 14, 30), now) shouldBe true
		placeAtNow(LocalDateTime.of(2026, 10, 22, 16, 30), now) shouldBe false
	}

	test("focus: the channel played last when it changed (Channel Up / Down), else the one focused") {
		placeFocus(place(focus = 301, lastPlayedAtLeave = 12), lastPlayed = ids[302]) shouldBe ids[302]
		// picked 301 again, up to 302, down to 301: 301
		placeFocus(place(focus = 301, lastPlayedAtLeave = 301), lastPlayed = ids[301]) shouldBe ids[301]
		// a programme's page, nothing played: the one focused
		placeFocus(place(focus = 150, lastPlayedAtLeave = 12), lastPlayed = ids[12]) shouldBe ids[150]
		placeFocus(place(focus = null, lastPlayedAtLeave = null), lastPlayed = null) shouldBe null
	}

	test("the channel page left, while it has the channel to focus") {
		placePageStart(ids, place(), ids[301], pageSize = 75) shouldBe 263
		placePageStart(ids, place(), null, pageSize = 75) shouldBe 263
		// Channel Up went past the page: the page goes around that channel instead
		placePageStart(ids, place(), ids[338], pageSize = 75) shouldBe null
		// rows came meanwhile: the same first channel, wherever it is now
		val more = listOf(UUID(1, 1), UUID(1, 2)) + ids
		placePageStart(more, place(), ids[301], pageSize = 75) shouldBe 265
		// the page's first channel went
		placePageStart(ids - ids[263], place(), ids[301], pageSize = 75) shouldBe null
	}

	test("the rows: the top one as far past its top as before; the focused one always in view") {
		// rows 100 px, 600 px of guide; the top row at 3,600, focus three rows below: as left
		placeScrollY(topRowTop = 3600, topOffset = 20, focusRowTop = 3900, rowHeight = 100, viewHeight = 600) shouldBe 3620
		// Channel Up to the row just below the screen: one row further, not more
		placeScrollY(topRowTop = 3600, topOffset = 20, focusRowTop = 4200, rowHeight = 100, viewHeight = 600) shouldBe 3700
		// Channel Down to the row just above: that row at the top
		placeScrollY(topRowTop = 3600, topOffset = 20, focusRowTop = 3500, rowHeight = 100, viewHeight = 600) shouldBe 3500
		// focus far below (Channel Up a lot): it in the middle
		placeScrollY(topRowTop = 3600, topOffset = 20, focusRowTop = 6000, rowHeight = 100, viewHeight = 600) shouldBe 5750
		// the top row went: from the top, the focused one shown
		placeScrollY(topRowTop = null, topOffset = 20, focusRowTop = 400, rowHeight = 100, viewHeight = 600) shouldBe 0
		placeScrollY(topRowTop = null, topOffset = 20, focusRowTop = 900, rowHeight = 100, viewHeight = 600) shouldBe 400
		placeScrollY(topRowTop = null, topOffset = 0, focusRowTop = null, rowHeight = 100, viewHeight = 600) shouldBe 0
	}
})
