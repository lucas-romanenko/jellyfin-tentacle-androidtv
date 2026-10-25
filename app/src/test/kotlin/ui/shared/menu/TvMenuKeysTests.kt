package org.jellyfin.androidtv.ui.shared.menu

import android.view.KeyEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.ui.shared.menu.OkLongPress.Verdict

class TvMenuKeysTests : FunSpec({
	val ok = KeyEvent.KEYCODE_DPAD_CENTER
	val down = KeyEvent.ACTION_DOWN
	val up = KeyEvent.ACTION_UP

	test("the long press that opened the menu: its repeats and release are dropped") {
		val gate = OkKeyGate()
		gate.swallow(down, ok, 3) shouldBe true
		gate.swallow(down, ok, 4) shouldBe true
		gate.swallow(up, ok, 0) shouldBe true
	}

	test("a fresh OK press in the menu goes through, and so does its release") {
		val gate = OkKeyGate()
		gate.swallow(up, ok, 0) shouldBe true
		gate.swallow(down, ok, 0) shouldBe false
		gate.swallow(up, ok, 0) shouldBe false
	}

	test("a menu opened without OK held (the Menu key): the first OK works") {
		val gate = OkKeyGate()
		gate.swallow(down, KeyEvent.KEYCODE_ENTER, 0) shouldBe false
		gate.swallow(up, KeyEvent.KEYCODE_ENTER, 0) shouldBe false
	}

	test("moving and Back are never held back") {
		val gate = OkKeyGate()
		gate.swallow(down, KeyEvent.KEYCODE_DPAD_DOWN, 0) shouldBe false
		gate.swallow(up, KeyEvent.KEYCODE_BACK, 0) shouldBe false
		// and OK is still gated after them
		gate.swallow(up, ok, 0) shouldBe true
	}

	test("Home: a short press is an ordinary click") {
		val press = OkLongPress()
		press.onKey(down, ok, 0, false) shouldBe Verdict.PASS
		press.onKey(up, ok, 0, false) shouldBe Verdict.PASS
	}

	test("Home: a long press opens the menu once and keeps its repeats and release from the card") {
		val press = OkLongPress()
		press.onKey(down, ok, 0, false) shouldBe Verdict.PASS
		press.onKey(down, ok, 1, true) shouldBe Verdict.OPEN_MENU
		press.onKey(down, ok, 2, false) shouldBe Verdict.SWALLOW
		press.onKey(up, ok, 0, false) shouldBe Verdict.SWALLOW
		// the next press is a click again
		press.onKey(down, ok, 0, false) shouldBe Verdict.PASS
		press.onKey(up, ok, 0, false) shouldBe Verdict.PASS
	}

	test("Home: a long press on a card without a menu clicks on release as before") {
		val press = OkLongPress()
		press.onKey(down, ok, 0, false)
		press.onKey(down, ok, 1, true) shouldBe Verdict.OPEN_MENU
		press.noMenu()
		press.onKey(down, ok, 2, false) shouldBe Verdict.PASS
		press.onKey(up, ok, 0, false) shouldBe Verdict.PASS
	}

	test("Home: repeats without their first press (focus came mid-press) are left alone") {
		val press = OkLongPress()
		press.onKey(down, ok, 5, false) shouldBe Verdict.PASS
		press.onKey(up, ok, 0, false) shouldBe Verdict.PASS
	}

	test("Home: other keys pass") {
		val press = OkLongPress()
		press.onKey(down, KeyEvent.KEYCODE_DPAD_RIGHT, 3, false) shouldBe Verdict.PASS
	}
})
