package org.jellyfin.androidtv.ui.browsing

import androidx.fragment.app.Fragment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.ui.home.HomeFragment

/** Home keeps its view under the screens opened from it, so Back doesn't build it again. */
class KeepHomeViewTests : FunSpec({
	test("Home covered by a details page keeps its view") {
		keepsViewWhileCovered(HomeFragment::class.java, staysInHistory = true) shouldBe true
	}

	test("Home leaving the history (a reset, a replace) is detached as before") {
		keepsViewWhileCovered(HomeFragment::class.java, staysInHistory = false) shouldBe false
	}

	test("other screens are detached as before") {
		keepsViewWhileCovered(Fragment::class.java, staysInHistory = true) shouldBe false
	}
})
