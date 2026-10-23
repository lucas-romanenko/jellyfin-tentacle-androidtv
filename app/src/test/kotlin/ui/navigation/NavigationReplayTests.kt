package org.jellyfin.androidtv.ui.navigation

import android.os.Bundle
import androidx.fragment.app.Fragment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk

/**
 * MainActivity collects navigation only while started, and the last action is kept for a
 * collector that isn't there yet. Once handled it must not run again on the next start: the
 * start-up reset built a new Home every time the TV woke.
 */
class NavigationReplayTests : FunSpec({
	fun destination() = Destination.Fragment(Fragment::class, mockk<Bundle>(relaxed = true))

	test("an action not handled yet is kept for the next collector") {
		val repository = NavigationRepositoryImpl(destination())
		repository.navigate(destination())
		repository.currentAction.replayCache.size shouldBe 1
	}

	test("a handled action isn't replayed on the next start") {
		val repository = NavigationRepositoryImpl(destination())
		repository.reset(clearHistory = true)
		val action = repository.currentAction.replayCache.single()
		repository.onActionHandled(action)
		repository.currentAction.replayCache.shouldBeEmpty()
	}

	test("Back, handled, isn't replayed either") {
		val repository = NavigationRepositoryImpl(destination())
		repository.navigate(destination())
		repository.onActionHandled(repository.currentAction.replayCache.single())
		repository.goBack()
		repository.onActionHandled(NavigationAction.GoBack)
		repository.currentAction.replayCache.shouldBeEmpty()
	}

	test("a newer action than the handled one is still kept") {
		val repository = NavigationRepositoryImpl(destination())
		repository.navigate(destination())
		val first = repository.currentAction.replayCache.single()
		repository.goBack()
		repository.onActionHandled(first)
		repository.currentAction.replayCache shouldContainExactly listOf(NavigationAction.GoBack)
	}
})
