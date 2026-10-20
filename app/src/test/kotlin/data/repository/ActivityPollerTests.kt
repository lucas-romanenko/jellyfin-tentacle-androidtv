package org.jellyfin.androidtv.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The app's one activity poller: how often it asks (M9). */
class ActivityPollerTests : FunSpec({
	test("every 3 s while the Activity tab is open, downloads or not") {
		activityPollMs(closely = true, downloads = 0) shouldBe 3_000L
		activityPollMs(closely = true, downloads = 2) shouldBe 3_000L
	}

	test("for the navbar badge: 10 s while something downloads, else 30 s") {
		activityPollMs(closely = false, downloads = 1) shouldBe 10_000L
		activityPollMs(closely = false, downloads = 0) shouldBe 30_000L
	}
})
