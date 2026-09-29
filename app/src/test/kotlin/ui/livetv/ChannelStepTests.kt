package org.jellyfin.androidtv.ui.livetv

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Channel Up / Channel Down move through the guide's channels and wrap (#56). */
class ChannelStepTests : FunSpec({
	test("up is the next channel, down the previous") {
		steppedChannelIndex(current = 2, count = 5, up = true) shouldBe 3
		steppedChannelIndex(current = 2, count = 5, up = false) shouldBe 1
	}

	test("it wraps at both ends") {
		steppedChannelIndex(current = 4, count = 5, up = true) shouldBe 0
		steppedChannelIndex(current = 0, count = 5, up = false) shouldBe 4
	}

	test("an unknown current channel starts at the first; no channels, nowhere to go") {
		steppedChannelIndex(current = -1, count = 5, up = true) shouldBe 0
		steppedChannelIndex(current = 0, count = 0, up = true) shouldBe -1
	}
})
