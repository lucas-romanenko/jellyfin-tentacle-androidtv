package org.jellyfin.androidtv.ui.shared.menu

import android.view.KeyEvent

/**
 * Keeps a menu from acting on the press that opened it. A long press of OK opens a menu while OK is
 * still held: the TV keeps sending that press's repeats and finally its release, now to the menu's
 * window, and Compose's clickable takes a repeat for a new press and the release for a click, so the
 * menu's first choice ran the moment OK was let go (#219, "it instantly selects the resume").
 *
 * The gate lets OK through only after a fresh press inside the menu (a down with no repeats). Every
 * other key passes, so the menu can be moved in and closed with Back at once.
 */
class OkKeyGate {
	private var armed = false

	/** True when this key event belongs to the press that opened the menu and must be dropped. */
	fun swallow(action: Int, keyCode: Int, repeatCount: Int): Boolean {
		if (!isOkKey(keyCode)) return false
		if (armed) return false
		if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
			armed = true
			return false
		}
		return true
	}

	companion object {
		fun isOkKey(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
			keyCode == KeyEvent.KEYCODE_ENTER ||
			keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
			keyCode == KeyEvent.KEYCODE_BUTTON_A
	}
}
