package org.jellyfin.androidtv.ui.home.mediabar

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random

/** The hero pauses while the app is in the background, and only then (a8/04). */
class HeroPauseTests : FunSpec({
	test("Home button with the hero focused: pause, then focus it again on return") {
		val pause = HeroPause()
		pause.onStop(heroFocused = true, leavingByNavigation = false) shouldBe true
		pause.onResume() shouldBe true
		pause.onResume() shouldBe false
	}

	test("the hero wasn't focused: nothing to pause or restore") {
		val pause = HeroPause()
		pause.onStop(heroFocused = false, leavingByNavigation = false) shouldBe false
		pause.onResume() shouldBe false
	}

	test("navigating away detaches the fragment: its view takes the focus away, not us") {
		val pause = HeroPause()
		pause.onStop(heroFocused = true, leavingByNavigation = true) shouldBe false
		pause.onResume() shouldBe false
	}

	test("paused in the background, then a deep link replaced the view: the new view's focus decides") {
		val pause = HeroPause()
		pause.onStop(heroFocused = true, leavingByNavigation = false) shouldBe true
		pause.onDestroyView()
		pause.onResume() shouldBe false
	}

	test("any lifecycle order: focus is only ever given back after a pause of a focused hero, once") {
		repeat(1000) { seed ->
			val random = Random(seed)
			val pause = HeroPause()
			var owed = false // a pause of a focused hero not yet answered by a resume or a new view
			repeat(40) {
				when (random.nextInt(3)) {
					0 -> {
						val focused = random.nextBoolean()
						val navigating = random.nextBoolean()
						val paused = pause.onStop(focused, navigating)
						paused shouldBe (focused && !navigating)
						if (paused) owed = true
					}
					1 -> {
						pause.onResume() shouldBe owed
						owed = false
					}
					else -> {
						pause.onDestroyView()
						owed = false
					}
				}
			}
		}
	}
})
