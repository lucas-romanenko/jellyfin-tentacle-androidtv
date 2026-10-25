package org.jellyfin.androidtv.ui.livetv

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.ui.livetv.GuideProgrammeChoice.CANCEL_RECORDING
import org.jellyfin.androidtv.ui.livetv.GuideProgrammeChoice.CANCEL_SERIES
import org.jellyfin.androidtv.ui.livetv.GuideProgrammeChoice.DETAILS
import org.jellyfin.androidtv.ui.livetv.GuideProgrammeChoice.RECORD
import org.jellyfin.androidtv.ui.livetv.GuideProgrammeChoice.RECORD_SERIES
import org.jellyfin.androidtv.ui.livetv.GuideProgrammeChoice.TUNE_TO_CHANNEL
import org.jellyfin.androidtv.ui.livetv.GuideProgrammeChoice.WATCH_NOW
import java.time.LocalDateTime

class GuideMenusTests : FunSpec({
	val now = LocalDateTime.of(2026, 10, 24, 20, 30)

	fun choices(
		start: LocalDateTime = now.minusMinutes(30),
		end: LocalDateTime = now.plusMinutes(30),
		canManage: Boolean = true,
		timerId: String? = null,
		seriesTimerId: String? = null,
		isSeries: Boolean = false,
	) = guideProgrammeChoices(start, end, now, canManage, timerId, seriesTimerId, isSeries)

	test("on now: Watch now, Record this, Details; never a Favorite") {
		choices() shouldBe listOf(WATCH_NOW, RECORD, DETAILS)
	}

	test("a series later tonight: Tune to channel, Record this, Record every episode, Details") {
		choices(start = now.plusHours(1), end = now.plusHours(2), isSeries = true) shouldBe
			listOf(TUNE_TO_CHANNEL, RECORD, RECORD_SERIES, DETAILS)
	}

	test("already booked: the cancels instead") {
		choices(timerId = "t", seriesTimerId = "s", isSeries = true) shouldBe
			listOf(WATCH_NOW, CANCEL_RECORDING, CANCEL_SERIES, DETAILS)
	}

	test("someone who may not manage recordings: only watch and details") {
		choices(canManage = false, isSeries = true) shouldBe listOf(WATCH_NOW, DETAILS)
	}

	test("over: nothing to record") {
		choices(start = now.minusHours(2), end = now.minusHours(1), isSeries = true) shouldBe listOf(TUNE_TO_CHANNEL, DETAILS)
	}

	test("a channel's name: star at once, ask before taking it off") {
		guideFavoriteStep(isFavorite = false) shouldBe GuideFavoriteStep.ADD
		guideFavoriteStep(isFavorite = true) shouldBe GuideFavoriteStep.CONFIRM_REMOVE
	}
})
