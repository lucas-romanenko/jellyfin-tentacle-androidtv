package org.jellyfin.androidtv.ui.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Only the newest Live TV tune's answer may start, whatever order the answers come in (#176). */
class LiveTuneAttemptsTests : FunSpec({
	test("a single tune is current") {
		val tunes = LiveTuneAttempts()
		val first = tunes.start()
		tunes.isCurrent(first) shouldBe true
	}

	test("CH+ twice: the older answer, early or late, no longer plays") {
		val tunes = LiveTuneAttempts()
		val first = tunes.start()
		val second = tunes.start()
		tunes.isCurrent(first) shouldBe false
		tunes.isCurrent(second) shouldBe true
	}
})
