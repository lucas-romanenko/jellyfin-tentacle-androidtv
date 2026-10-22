package org.jellyfin.androidtv.ui.presentation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.preference.constant.WatchedIndicatorBehavior
import org.jellyfin.sdk.model.api.BaseItemKind

/** The View-based cards keep the sizes and badges the Compose cards had. */
class CardLayoutTests : FunSpec({
	test("a poster in a home row takes the poster size setting's height") {
		cardSizeDp(0.5f, staticHeight = true, presenterHeight = 150, posterHeight = 150, posterLandscapeHeight = 110) shouldBe
			CardSizeDp(75f, 150f)
	}

	test("a wide card in a home row uses the shorter landscape height") {
		cardSizeDp(16f / 9f, staticHeight = true, presenterHeight = 150, posterHeight = 150, posterLandscapeHeight = 110).height shouldBe 110f
	}

	test("a presenter with its own height ignores the poster size setting") {
		cardSizeDp(1f, staticHeight = true, presenterHeight = 200, posterHeight = 150, posterLandscapeHeight = 110) shouldBe
			CardSizeDp(200f, 200f)
		cardSizeDp(2f, staticHeight = true, presenterHeight = 200, posterHeight = 150, posterLandscapeHeight = 110).height shouldBe 146f
	}

	test("rows without a static height use 150 for posters and 130 for wide cards") {
		cardSizeDp(0.5f, staticHeight = false, presenterHeight = 150, posterHeight = 220, posterLandscapeHeight = 160) shouldBe
			CardSizeDp(75f, 150f)
		cardSizeDp(2f, staticHeight = false, presenterHeight = 150, posterHeight = 220, posterLandscapeHeight = 160) shouldBe
			CardSizeDp(260f, 130f)
	}

	test("watched badge follows the indicator setting") {
		watchBadge(WatchedIndicatorBehavior.ALWAYS, BaseItemKind.MOVIE, played = true, unplayedCount = null) shouldBe WatchBadge.Played
		watchBadge(WatchedIndicatorBehavior.ALWAYS, BaseItemKind.SERIES, played = false, unplayedCount = 4) shouldBe WatchBadge.Unplayed(4)
		watchBadge(WatchedIndicatorBehavior.ALWAYS, BaseItemKind.SERIES, played = false, unplayedCount = 0) shouldBe WatchBadge.None
		watchBadge(WatchedIndicatorBehavior.NEVER, BaseItemKind.MOVIE, played = true, unplayedCount = null) shouldBe WatchBadge.None
		watchBadge(WatchedIndicatorBehavior.HIDE_UNWATCHED, BaseItemKind.SERIES, played = false, unplayedCount = 4) shouldBe WatchBadge.None
		watchBadge(WatchedIndicatorBehavior.HIDE_UNWATCHED, BaseItemKind.MOVIE, played = true, unplayedCount = null) shouldBe WatchBadge.Played
		watchBadge(WatchedIndicatorBehavior.EPISODES_ONLY, BaseItemKind.MOVIE, played = true, unplayedCount = null) shouldBe WatchBadge.None
		watchBadge(WatchedIndicatorBehavior.EPISODES_ONLY, BaseItemKind.EPISODE, played = true, unplayedCount = null) shouldBe WatchBadge.Played
	}

	test("the progress bar shows only for a started, unfinished item") {
		playedFraction(null).shouldBeNull()
		playedFraction(0.0).shouldBeNull()
		playedFraction(100.0).shouldBeNull()
		playedFraction(25.0) shouldBe 0.25f
	}

	test("card previews follow the server's policy") {
		previewAllowedByServer("off", hasLocalMedia = true) shouldBe false
		previewAllowedByServer("local_only", hasLocalMedia = false) shouldBe false
		previewAllowedByServer("local_only", hasLocalMedia = true) shouldBe true
		previewAllowedByServer("all", hasLocalMedia = false) shouldBe true
		previewAllowedByServer("something-new", hasLocalMedia = false) shouldBe true
	}
})
