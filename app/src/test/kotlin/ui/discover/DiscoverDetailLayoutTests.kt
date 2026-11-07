package org.jellyfin.androidtv.ui.discover

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual

/**
 * #249: on a 1080p TV (540 dp high) the Discover detail dialog opened scrolled down to its
 * buttons, with the rating, tagline and overview above the top edge: the buttons were the last
 * thing in a 147 dp scroll area holding about 290 dp. They're pinned under the text now, and
 * the text has to fit above them without scrolling.
 */
class DiscoverDetailLayoutTests : FunSpec({
	test("the whole text fits above the pinned buttons on a 1080p TV") {
		DiscoverDetailLayout.textDp() shouldBeLessThanOrEqual DiscoverDetailLayout.textRoomDp(540)
	}

	test("the old sizes didn't: 280 dp backdrop, 5-line overview, buttons inside the scroll area") {
		val panel = (540 * DiscoverDetailLayout.PANEL_HEIGHT_FRACTION).toInt()
		val oldRoom = panel - 280 - 16 - 16
		val oldContent = (19 + 12) + (19 + 8) + (5 * 22 + 16) + (20 + 4 + 18 + 12) + (20 + 4 + 18 + 16) + 40
		oldContent shouldBeGreaterThan oldRoom
	}
})
