package org.jellyfin.androidtv.ui.navigation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.androidtv.ui.home.HomeFragment
import org.jellyfin.androidtv.ui.search.SearchFragment

/**
 * The toolbar's Home button (Navbar, LeftSidebarNavigation: goHome) goes back to Home from any
 * depth. The screen the app opened on is not in the repository's history: reset() empties it, and
 * DestinationFragmentView keeps that first screen as the root of its own history. So one screen
 * above Home the history holds one entry, and Back on Home finds nothing to go back to.
 */
class NavigationGoHomeTests : FunSpec({
	// Bundle is an Android stub in JVM tests: a relaxed mock with a toString keeps Timber's templates working
	fun args() = mockk<android.os.Bundle>(relaxed = true) { every { this@mockk.toString() } returns "Bundle[]" }
	val home = Destination.Fragment(HomeFragment::class, args())
	fun search() = Destination.Fragment(SearchFragment::class, args())
	val openHome = NavigationAction.NavigateFragment(home, addToBackStack = true, replace = false, clear = true)

	/** The app as StartupActivity leaves it: [start] (null: Home) in front, its action handled. */
	fun startedOn(start: Destination.Fragment? = null) = NavigationRepositoryImpl(home).apply {
		reset(start, clearHistory = true)
		onActionHandled(currentAction.replayCache.single())
	}

	test("one screen above Home, Home goes back to Home") {
		val repository = startedOn()
		repository.navigate(search())
		repository.goHome()
		repository.currentAction.replayCache.single() shouldBe NavigationAction.PopToFirst
		repository.canGoBack shouldBe false
	}

	test("two screens above Home, Home leaves no entry behind for the next Back") {
		val repository = startedOn()
		repository.navigate(search())
		repository.navigate(search())
		repository.goHome()
		repository.currentAction.replayCache.single() shouldBe NavigationAction.PopToFirst
		// Back on Home: the toolbar, then the exit question (MainActivity), not a Back that does nothing
		repository.canGoBack shouldBe false
	}

	test("on Home, Home does nothing") {
		val repository = startedOn()
		repository.goHome()
		repository.currentAction.replayCache.shouldBeEmpty()
		repository.canGoBack shouldBe false
	}

	test("opened on another screen (a launcher row's title, a voice search), Home opens Home") {
		val repository = startedOn(search())
		repository.goHome()
		repository.currentAction.replayCache.single() shouldBe openHome
		repository.canGoBack shouldBe false
	}

	test("opened on another screen and one screen further, Home opens Home, not that first screen") {
		val repository = startedOn(search())
		repository.navigate(search())
		repository.navigate(search())
		repository.goHome()
		repository.currentAction.replayCache.single() shouldBe openHome
		repository.canGoBack shouldBe false
	}

	test("once Home is the first screen again, Home goes back to it without building a new one") {
		val repository = startedOn(search())
		repository.reset(home, clearHistory = true) // the clock's Home button
		repository.navigate(search())
		repository.goHome()
		repository.currentAction.replayCache.single() shouldBe NavigationAction.PopToFirst
		repository.canGoBack shouldBe false
	}
})
