package org.jellyfin.androidtv.ui.shared.menu

import android.view.KeyEvent

/**
 * A long press of OK on a screen whose cards are Views (Home's Leanback rows): the card itself only
 * knows clicks, and would open on the release. Fed every key event before the card sees it, this
 * says when the press became a long one (open the menu) and which of its events to keep from the
 * card (its repeats and its release), so a long press never also opens the card.
 */
class OkLongPress {
	enum class Verdict {
		/** Not part of a long press: the card handles it as usual. */
		PASS,

		/** The press just became a long one: open the menu (and drop this event). */
		OPEN_MENU,

		/** Part of a long press already handled: drop it. */
		SWALLOW,
	}

	private var held = false
	private var opened = false

	/** OPEN_MENU found no menu to open: the press goes on as an ordinary one (its release clicks). */
	fun noMenu() {
		opened = false
		held = false
	}

	fun onKey(action: Int, keyCode: Int, repeatCount: Int, isLongPress: Boolean): Verdict {
		if (!OkKeyGate.isOkKey(keyCode)) return Verdict.PASS
		return when (action) {
			KeyEvent.ACTION_DOWN -> when {
				repeatCount == 0 -> {
					held = true
					opened = false
					Verdict.PASS
				}
				opened -> Verdict.SWALLOW
				held && (isLongPress || repeatCount >= 1) -> {
					opened = true
					Verdict.OPEN_MENU
				}
				else -> Verdict.PASS
			}
			KeyEvent.ACTION_UP -> {
				val wasOpened = opened
				held = false
				opened = false
				if (wasOpened) Verdict.SWALLOW else Verdict.PASS
			}
			else -> Verdict.PASS
		}
	}
}
