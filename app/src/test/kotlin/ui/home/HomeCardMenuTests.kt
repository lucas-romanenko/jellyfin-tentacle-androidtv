package org.jellyfin.androidtv.ui.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.constant.QueryType
import org.jellyfin.androidtv.ui.home.HomeCardChoice.DETAILS
import org.jellyfin.androidtv.ui.home.HomeCardChoice.MARK_UNWATCHED
import org.jellyfin.androidtv.ui.home.HomeCardChoice.MARK_WATCHED
import org.jellyfin.androidtv.ui.home.HomeCardChoice.PLAY_FROM_START
import org.jellyfin.androidtv.ui.home.HomeCardChoice.REMOVE_FROM_CONTINUE_WATCHING
import org.jellyfin.sdk.model.api.BaseItemKind

class HomeCardMenuTests : FunSpec({
	test("Continue Watching, a title with a saved place") {
		homeCardChoices(inContinueWatching = true, hasPosition = true, played = false) shouldBe
			listOf(REMOVE_FROM_CONTINUE_WATCHING, PLAY_FROM_START, MARK_WATCHED, DETAILS)
	}

	test("a Next Up episode in the merged row has no place to remove") {
		homeCardChoices(inContinueWatching = true, hasPosition = false, played = false) shouldBe listOf(MARK_WATCHED, DETAILS)
	}

	test("other rows: watched or unwatched, then details") {
		homeCardChoices(inContinueWatching = false, hasPosition = true, played = false) shouldBe listOf(MARK_WATCHED, DETAILS)
		homeCardChoices(inContinueWatching = false, hasPosition = false, played = true) shouldBe listOf(MARK_UNWATCHED, DETAILS)
	}

	test("Continue Watching rows, on their own or merged") {
		isContinueWatchingQuery(QueryType.Resume) shouldBe true
		isContinueWatchingQuery(QueryType.MergedContinueWatching) shouldBe true
		isContinueWatchingQuery(QueryType.NextUp) shouldBe false
		isContinueWatchingQuery(null) shouldBe false
	}

	test("cards that can be watched have a menu; channels, programmes and folders keep OK as it was") {
		homeCardHasMenu(BaseItemKind.MOVIE) shouldBe true
		homeCardHasMenu(BaseItemKind.EPISODE) shouldBe true
		homeCardHasMenu(BaseItemKind.SERIES) shouldBe true
		homeCardHasMenu(BaseItemKind.TV_CHANNEL) shouldBe false
		homeCardHasMenu(BaseItemKind.PROGRAM) shouldBe false
		homeCardHasMenu(BaseItemKind.COLLECTION_FOLDER) shouldBe false
		homeCardHasMenu(null) shouldBe false
	}
})
