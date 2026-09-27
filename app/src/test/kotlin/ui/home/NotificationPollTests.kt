package org.jellyfin.androidtv.ui.home

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** The Home notification poll backs off while the server is down (#49). */
class NotificationPollTests : FunSpec({
	test("no answer doubles the wait, up to two minutes") {
		nextNotificationPoll(15_000, answered = false) shouldBe 30_000
		nextNotificationPoll(60_000, answered = false) shouldBe 120_000
		nextNotificationPoll(120_000, answered = false) shouldBe 120_000
	}

	test("the first answer brings it back to 15 s") {
		nextNotificationPoll(120_000, answered = true) shouldBe 15_000
		nextNotificationPoll(15_000, answered = true) shouldBe 15_000
	}
})
