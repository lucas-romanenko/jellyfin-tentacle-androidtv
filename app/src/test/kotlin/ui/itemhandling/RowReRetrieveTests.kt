package org.jellyfin.androidtv.ui.itemhandling

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.constant.ChangeTriggerType
import org.jellyfin.androidtv.data.model.DataRefreshService
import java.time.Instant

/** Which home rows load again when Home comes back from the player. */
class RowReRetrieveTests : FunSpec({
	val loaded = Instant.parse("2026-10-16T03:00:00Z")
	val continueWatching = arrayOf(ChangeTriggerType.TvPlayback, ChangeTriggerType.MoviePlayback)
	val nextUp = arrayOf(ChangeTriggerType.TvPlayback)
	val latest = arrayOf(ChangeTriggerType.LibraryUpdated)

	test("after a movie was played, Continue Watching loads again and Next Up does not") {
		val refresh = DataRefreshService().apply { lastMoviePlayback = loaded.plusSeconds(90) }
		RowReRetrieve.isDue(continueWatching, loaded, refresh) shouldBe true
		RowReRetrieve.isDue(nextUp, loaded, refresh) shouldBe false
	}

	test("after an episode was played, Continue Watching and Next Up both load again") {
		val refresh = DataRefreshService().apply { lastTvPlayback = loaded.plusSeconds(90) }
		RowReRetrieve.isDue(continueWatching, loaded, refresh) shouldBe true
		RowReRetrieve.isDue(nextUp, loaded, refresh) shouldBe true
	}

	test("nothing changed since the row loaded: no request") {
		val refresh = DataRefreshService().apply {
			lastMoviePlayback = loaded.minusSeconds(60)
			lastLibraryChange = loaded.minusSeconds(5)
		}
		RowReRetrieve.isDue(continueWatching, loaded, refresh) shouldBe false
		RowReRetrieve.isDue(latest, loaded, refresh) shouldBe false
		RowReRetrieve.isDue(continueWatching, loaded, DataRefreshService()) shouldBe false
	}

	test("a library change reloads Recently Added") {
		val refresh = DataRefreshService().apply { lastLibraryChange = loaded.plusSeconds(1) }
		RowReRetrieve.isDue(latest, loaded, refresh) shouldBe true
	}

	test("a row still on its first load, or without triggers, is left alone") {
		val refresh = DataRefreshService().apply { lastMoviePlayback = loaded }
		RowReRetrieve.isDue(continueWatching, null, refresh) shouldBe false
		RowReRetrieve.isDue(null, loaded, refresh) shouldBe false
		RowReRetrieve.isDue(arrayOf(ChangeTriggerType.MusicPlayback), loaded, refresh) shouldBe false
	}
})
