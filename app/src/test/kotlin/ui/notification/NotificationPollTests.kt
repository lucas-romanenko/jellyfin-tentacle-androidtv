package org.jellyfin.androidtv.ui.notification

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.data.repository.TentacleNotification

/** The notification poll backs off while the server is down (#49). */
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

	test("the toast's heading says what the message is about") {
		notificationHeading(TentacleNotification(title = "Dune", kind = "download")) shouldBe "Ready to Watch"
		notificationHeading(TentacleNotification(title = "Dune")) shouldBe "Ready to Watch"
		notificationHeading(TentacleNotification(title = "Dune", kind = "request")) shouldBe "Request"
		notificationHeading(TentacleNotification(title = "Live TV is down", kind = "problem")) shouldBe "Live TV is down"
	}

	test("someone else's notification says whose it is") {
		notificationHeading(TentacleNotification(title = "Dune", kind = "download", forName = "Abbie")) shouldBe "Ready to Watch \u00b7 for Abbie"
		notificationHeading(TentacleNotification(title = "Dune", kind = "request", forName = "Abbie")) shouldBe "Request \u00b7 for Abbie"
		notificationHeading(TentacleNotification(title = "Dune", kind = "request", forName = "")) shouldBe "Request"
	}
})
