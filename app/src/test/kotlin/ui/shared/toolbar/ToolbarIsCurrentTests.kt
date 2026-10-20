package org.jellyfin.androidtv.ui.shared.toolbar

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.data.repository.ToolbarResponse

/** A new screen's navbar reuses this session's toolbar only while it is current (M9). */
class ToolbarIsCurrentTests : FunSpec({
	val toolbar = ToolbarResponse(homeVersion = 7)

	test("same home version as the newest heard: no request") {
		toolbarIsCurrent(toolbar, knownVersion = 7) shouldBe true
	}

	test("a newer version was heard: ask again") {
		toolbarIsCurrent(toolbar, knownVersion = 8) shouldBe false
	}

	test("no toolbar yet, or an older plugin without versions: ask, as before") {
		toolbarIsCurrent(null, knownVersion = 7) shouldBe false
		toolbarIsCurrent(ToolbarResponse(homeVersion = 0), knownVersion = 0) shouldBe false
	}
})
