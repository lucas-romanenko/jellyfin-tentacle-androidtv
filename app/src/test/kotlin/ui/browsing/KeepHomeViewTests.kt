package org.jellyfin.androidtv.ui.browsing

import androidx.fragment.app.Fragment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.ui.discover.DiscoverFragment
import org.jellyfin.androidtv.ui.home.HomeFragment

/** Home and Discover keep their view under the screens opened from them, so Back doesn't build them again. */
class KeepHomeViewTests : FunSpec({
	test("Home covered by a details page keeps its view") {
		keepsViewWhileCovered(HomeFragment::class.java, staysInHistory = true) shouldBe true
	}

	test("Home leaving the history (a reset, a replace) is detached as before") {
		keepsViewWhileCovered(HomeFragment::class.java, staysInHistory = false) shouldBe false
	}

	test("Discover covered by a trailer keeps its view, so Back returns to the focused card") {
		keepsViewWhileCovered(DiscoverFragment::class.java, staysInHistory = true) shouldBe true
	}

	test("Discover leaving the history is detached as before") {
		keepsViewWhileCovered(DiscoverFragment::class.java, staysInHistory = false) shouldBe false
	}

	test("other screens are detached as before") {
		keepsViewWhileCovered(Fragment::class.java, staysInHistory = true) shouldBe false
	}
})
