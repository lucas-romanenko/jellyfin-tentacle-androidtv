package org.jellyfin.androidtv.ui

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Multi-view's grid keeps the TV awake while it plays, and only then (Lucas, 25 Oct). */
class KeepAwakeWhilePlayingTests : FunSpec({
	class Locks {
		var taken = 0
		var given = 0
		val held get() = taken - given
		val keep = KeepAwakeWhilePlaying { taken++; { given++ } }
	}

	test("playing takes one lock, however often the player says so") {
		val l = Locks()
		l.keep.onIsPlayingChanged(true)
		l.keep.onIsPlayingChanged(true)
		l.held shouldBe 1
		l.taken shouldBe 1
		l.keep.held shouldBe true
	}

	test("paused, stalled or an error: the lock goes; playing again takes it back") {
		val l = Locks()
		l.keep.onIsPlayingChanged(true)
		l.keep.onIsPlayingChanged(false)
		l.held shouldBe 0
		l.keep.held shouldBe false
		l.keep.onIsPlayingChanged(true)
		l.held shouldBe 1
		l.taken shouldBe 2
	}

	test("letting the player go (Back, Home, full screen) gives the lock back once") {
		val l = Locks()
		l.keep.onIsPlayingChanged(true)
		l.keep.release()
		l.keep.release()
		l.held shouldBe 0
		l.given shouldBe 1
	}

	test("never played: nothing to give back") {
		val l = Locks()
		l.keep.onIsPlayingChanged(false)
		l.keep.release()
		l.taken shouldBe 0
		l.given shouldBe 0
	}
})
