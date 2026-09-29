package org.jellyfin.androidtv.ui.itemdetail.v2

import androidx.lifecycle.Lifecycle
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** A trailer opens only over the page that asked for it (#45). */
class TrailerLaunchTests : FunSpec({
	test("the page is in front: open") {
		trailerMayOpen(Lifecycle.State.RESUMED) shouldBe true
	}

	test("paused (Home button, a dialog activity), detached by forward navigation, or gone: don't") {
		trailerMayOpen(Lifecycle.State.STARTED) shouldBe false
		trailerMayOpen(Lifecycle.State.CREATED) shouldBe false
		trailerMayOpen(Lifecycle.State.INITIALIZED) shouldBe false
		trailerMayOpen(Lifecycle.State.DESTROYED) shouldBe false
	}
})
